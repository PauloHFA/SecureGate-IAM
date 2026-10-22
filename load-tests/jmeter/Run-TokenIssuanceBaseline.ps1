[CmdletBinding()]
param(
  [Parameter(Mandatory)][string]$JMeterHome,
  [Parameter(Mandatory)][uri]$BaseUri,
  [Parameter(Mandatory)][string]$MassFile,
  [Parameter(Mandatory)][string]$OutputDirectory,
  [ValidateRange(1, 20)][int]$Threads = 2,
  [ValidateRange(3, 900)][int]$WarmupSeconds = 10,
  [ValidateRange(5, 3600)][int]$SteadySeconds = 30,
  [ValidateRange(1, 300)][int]$WindowSeconds = 10,
  [ValidateRange(3, 900)][int]$CooldownSeconds = 10,
  [ValidateRange(0, 2147483647)][int]$IamProcessId = 0
)
$ErrorActionPreference = 'Stop'
if ($BaseUri.Host -notin @('127.0.0.1', 'localhost') -or $BaseUri.Scheme -notin @('http', 'https') -or
    $BaseUri.UserInfo -or $BaseUri.Query -or $BaseUri.Fragment -or $BaseUri.AbsolutePath -ne '/') {
  throw 'Use a loopback base URI without credentials, query or path.'
}
$jar = Join-Path $JMeterHome 'bin/ApacheJMeter.jar'
if (-not (Test-Path -LiteralPath $jar)) { throw 'JMeter binary is missing.' }
$mass = (Resolve-Path -LiteralPath $MassFile).Path
$rows = @(Import-Csv -LiteralPath $mass)
if ($WindowSeconds -gt $SteadySeconds) { throw 'WindowSeconds must not exceed SteadySeconds.' }
if ($rows.Count -lt $Threads) { throw 'Provide at least one synthetic client per thread.' }
foreach ($row in $rows) {
  if ($row.client_id -notmatch '^[a-z][a-z0-9_-]{2,63}$' -or [string]::IsNullOrWhiteSpace($row.client_secret) -or $row.scope -ne 'load:read') {
    throw 'Invalid synthetic mass file.'
  }
}
$output = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $output) { throw 'Output directory already exists; choose a new run directory.' }
[IO.Directory]::CreateDirectory($output) | Out-Null

function Get-Percentile([double[]]$values, [double]$fraction) {
  $sorted = @($values | Sort-Object)
  return [double]$sorted[[Math]::Ceiling($sorted.Count * $fraction) - 1]
}

function Get-SteadyWindows($samples, [int]$duration, [int]$width) {
  $timestamps = @($samples | ForEach-Object {
    [long]::Parse($_.timeStamp, [Globalization.CultureInfo]::InvariantCulture)
  })
  $origin = ($timestamps | Measure-Object -Minimum).Minimum
  $last = ($timestamps | Measure-Object -Maximum).Maximum
  $complete = [Math]::Min(
    [Math]::Floor($duration / $width),
    [Math]::Floor(($last - $origin) / ($width * 1000))
  )
  $buckets = @{}
  foreach ($sample in $samples) {
    $stamp = [long]::Parse($sample.timeStamp, [Globalization.CultureInfo]::InvariantCulture)
    $index = [int][Math]::Floor(($stamp - $origin) / ($width * 1000))
    if ($index -ge $complete) { continue }
    if (-not $buckets.ContainsKey($index)) {
      $buckets[$index] = [System.Collections.Generic.List[object]]::new()
    }
    $buckets[$index].Add($sample)
  }
  $windows = @()
  for ($index = 0; $index -lt $complete; $index++) {
    $bucket = if ($buckets.ContainsKey($index)) { @($buckets[$index].ToArray()) } else { @() }
    $elapsed = @($bucket | ForEach-Object {
      [double]::Parse($_.elapsed, [Globalization.CultureInfo]::InvariantCulture)
    })
    $failures = @($bucket | Where-Object { $_.success -ne 'true' }).Count
    $windows += [ordered]@{
      index = $index
      offsetSeconds = $index * $width
      durationSeconds = $width
      requests = $bucket.Count
      failures = $failures
      effectiveRequestsPerSecond = [Math]::Round($bucket.Count / $width, 2)
      p95Ms = if ($elapsed.Count -gt 0) { Get-Percentile $elapsed 0.95 } else { $null }
    }
  }
  return $windows
}

function Invoke-Phase([string]$name, [int]$phaseThreads, [int]$duration) {
  $folder = Join-Path $output $name
  [IO.Directory]::CreateDirectory($folder) | Out-Null
  $samplesFile = Join-Path $folder 'samples.csv'
  $resourceFile = Join-Path $folder 'iam-resources.csv'
  $stopFile = Join-Path $folder 'stop-sampling'
  $sampler = $null
  if ($IamProcessId -gt 0) {
    $sampler = Start-Job -ArgumentList $IamProcessId, $resourceFile, $stopFile -ScriptBlock {
      param($targetId, $path, $stop)
      while (-not (Test-Path -LiteralPath $stop)) {
        $process = Get-Process -Id $targetId -ErrorAction SilentlyContinue
        if ($null -ne $process) {
          [pscustomobject]@{
            timestampUtc = [DateTime]::UtcNow.ToString('o')
            cpuSeconds = $process.CPU
            workingSetBytes = $process.WorkingSet64
            privateBytes = $process.PrivateMemorySize64
          } | Export-Csv -LiteralPath $path -Append -NoTypeInformation
        }
        Start-Sleep -Seconds 1
      }
    }
  }
  try {
    $arguments = @(
      '-Xmx256m', '-jar', $jar, '-n', '-t', (Join-Path $PSScriptRoot 'token-issuance-baseline.jmx'),
      '-q', (Join-Path $PSScriptRoot 'safe-results.properties'),
      '-l', $samplesFile, '-j', (Join-Path $folder 'jmeter.log'),
      "-Jscheme=$($BaseUri.Scheme)", "-Jhost=$($BaseUri.Host)", "-Jport=$($BaseUri.Port)",
      "-Jmass_file=$mass", "-Jthreads=$phaseThreads", "-Jduration=$duration",
      "-Jramp_up=$([Math]::Min($duration, [Math]::Max(1, $phaseThreads)))",
      '-Lorg.apache.http=WARN'
    )
    & java @arguments *> (Join-Path $folder 'console.log')
    if ($LASTEXITCODE -ne 0) { throw "JMeter $name phase failed; inspect the local run log." }
  } finally {
    if ($null -ne $sampler) {
      [IO.File]::WriteAllText($stopFile, '')
      $null = Wait-Job -Job $sampler -Timeout 5
      if ($sampler.State -ne 'Completed') { Stop-Job -Job $sampler }
      Remove-Job -Job $sampler -Force
      Remove-Item -LiteralPath $stopFile -ErrorAction SilentlyContinue
    }
  }
  $samples = @(Import-Csv -LiteralPath $samplesFile)
  if ($samples.Count -eq 0) { throw "No $name samples were recorded." }
  if (@($samples | Where-Object { $_.label -ne 'token.success' }).Count -ne 0) {
    throw "Unexpected sample label in $name; do not mix flows in the token baseline."
  }
  $failures = @($samples | Where-Object { $_.success -ne 'true' }).Count
  $elapsed = @($samples | ForEach-Object { [double]::Parse($_.elapsed, [Globalization.CultureInfo]::InvariantCulture) })
  $windows = if ($name -eq 'steady') { @(Get-SteadyWindows $samples $duration $WindowSeconds) } else { @() }
  if ($name -eq 'steady' -and $windows.Count -eq 0) {
    throw 'No complete steady window was observed; extend the phase or reduce WindowSeconds.'
  }
  $resource = $null
  if ($IamProcessId -gt 0) {
    $points = @(Import-Csv -LiteralPath $resourceFile)
    if ($points.Count -lt 2) { throw "IAM resource samples missing in $name." }
    $first = $points[0]
    $last = $points[-1]
    $wall = ([DateTime]::Parse($last.timestampUtc) - [DateTime]::Parse($first.timestampUtc)).TotalSeconds
    $culture = [Globalization.CultureInfo]::CurrentCulture
    $cpu = ([double]::Parse($last.cpuSeconds, $culture) - [double]::Parse($first.cpuSeconds, $culture))
    $resource = [ordered]@{
      points = $points.Count
      maxWorkingSetMiB = [Math]::Round((@($points | ForEach-Object { [double]$_.workingSetBytes } | Measure-Object -Maximum)[0].Maximum / 1MB), 2)
      maxPrivateMiB = [Math]::Round((@($points | ForEach-Object { [double]$_.privateBytes } | Measure-Object -Maximum)[0].Maximum / 1MB), 2)
      averageCpuCorePercent = if ($wall -gt 0) { [Math]::Round(100 * $cpu / $wall, 2) } else { $null }
    }
  }
  return [ordered]@{
    phase = $name; threads = $phaseThreads; durationSeconds = $duration
    requests = $samples.Count; failures = $failures
    effectiveRequestsPerSecond = [Math]::Round($samples.Count / $duration, 2)
    errorPercent = [Math]::Round(100 * $failures / $samples.Count, 3)
    p50Ms = Get-Percentile $elapsed 0.50
    p95Ms = Get-Percentile $elapsed 0.95
    p99Ms = Get-Percentile $elapsed 0.99
    windows = $windows
    iamProcess = $resource
  }
}

$phases = @(
  (Invoke-Phase 'warmup' ([Math]::Max(1, [Math]::Ceiling($Threads / 2))) $WarmupSeconds)
  (Invoke-Phase 'steady' $Threads $SteadySeconds)
  (Invoke-Phase 'cooldown' 1 $CooldownSeconds)
)
$docker = $null
try {
  $docker = docker info --format '{{.MemTotal}} {{.NCPU}}' 2>$null
  if ($LASTEXITCODE -ne 0) { $docker = $null }
} catch { $docker = $null }
$summary = [ordered]@{
  timestampUtc = [DateTime]::UtcNow.ToString('o')
  scenario = 'client_credentials_token_issuance'
  scope = 'local baseline; separate JMeter phase processes; no production SLA claim'
  jmeterValidatedBaseline = '5.6.3'
  logicalProcessors = [Environment]::ProcessorCount
  os = [Environment]::OSVersion.VersionString
  dockerCapacity = $docker
  targetP95Ms = 200
  targetRequestsPerSecond = 300
  windowSeconds = $WindowSeconds
  windowMethod = 'windows anchored at first steady sample timestamp; only windows ending before the last observed sample start are included'
  phases = $phases
  steadyMeetsReferenceP95 = ($phases[1].p95Ms -lt 200)
  steadyMeetsReferenceRate = ($phases[1].effectiveRequestsPerSecond -ge 300)
}
$summary | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $output 'summary.json') -Encoding UTF8
if (@($phases | Where-Object { $_.failures -gt 0 }).Count -gt 0) {
  throw 'Token contract failed in at least one phase; inspect summary.json.'
}
Write-Output "Token baseline recorded: steady $($phases[1].effectiveRequestsPerSecond) req/s, p95 $($phases[1].p95Ms) ms."

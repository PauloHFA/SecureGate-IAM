[CmdletBinding()]
param(
  [Parameter(Mandatory)][string]$JMeterHome,
  [Parameter(Mandatory)][uri]$BaseUri,
  [Parameter()][string]$MassFile = '',
  [Parameter(Mandatory)][string]$OutputDirectory,
  [ValidateSet('issuance', 'introspection', 'resource', 'revocation', 'audit')][string]$Scenario = 'issuance',
  [string]$AdminTokenFile = '',
  [ValidateRange(1, 20)][int]$Threads = 2,
  [ValidateRange(3, 900)][int]$WarmupSeconds = 10,
  [ValidateRange(5, 3600)][int]$SteadySeconds = 30,
  [ValidateRange(1, 300)][int]$WindowSeconds = 10,
  [ValidateRange(3, 900)][int]$CooldownSeconds = 10,
  [ValidateRange(0, 2147483647)][int]$IamProcessId = 0,
  [string]$DockerContainerIds = '',
  [switch]$GenerateDashboard
)
$ErrorActionPreference = 'Stop'
if ($BaseUri.Host -notin @('127.0.0.1', 'localhost') -or $BaseUri.Scheme -notin @('http', 'https') -or
    $BaseUri.UserInfo -or $BaseUri.Query -or $BaseUri.Fragment -or $BaseUri.AbsolutePath -ne '/') {
  throw 'Use a loopback base URI without credentials, query or path.'
}
$jar = Join-Path $JMeterHome 'bin/ApacheJMeter.jar'
if (-not (Test-Path -LiteralPath $jar)) { throw 'JMeter binary is missing.' }
$mass = if (-not [string]::IsNullOrWhiteSpace($MassFile)) { (Resolve-Path -LiteralPath $MassFile).Path } else { $null }
$rows = if ($null -ne $mass) { @(Import-Csv -LiteralPath $mass) } else { @() }
if ($WindowSeconds -gt $SteadySeconds) { throw 'WindowSeconds must not exceed SteadySeconds.' }
if ($Scenario -in @('resource', 'revocation') -and [Math]::Max($WarmupSeconds, [Math]::Max($SteadySeconds, $CooldownSeconds)) -gt 90) {
  throw "$Scenario phases are limited to 90 seconds by the synthetic human token lifetime."
}
$adminFile = $null
if ($Scenario -eq 'audit') {
  if ([string]::IsNullOrWhiteSpace($AdminTokenFile)) { throw 'AdminTokenFile is required for audit scenario.' }
  $adminFile = (Resolve-Path -LiteralPath $AdminTokenFile).Path
  $adminToken = [IO.File]::ReadAllText($adminFile).Trim()
  if ([string]::IsNullOrWhiteSpace($adminToken) -or $adminToken -match '[\r\n]') { throw 'Invalid administrative token file.' }
  $adminToken = $null
} else {
  if ($null -eq $mass) { throw 'MassFile is required.' }
  if ($rows.Count -lt $Threads) { throw 'Provide at least one synthetic client per thread.' }
  foreach ($row in $rows) {
    if ($Scenario -in @('resource', 'revocation')) {
      $valid = $row.user_id -match '^[a-f0-9-]{36}$' -and $row.email -match '^[a-z0-9_-]+@example[.]test$' -and
        $row.password.Length -ge 12 -and $row.redirect_uri -eq 'http://127.0.0.1:18090/callback' -and
        $row.client_id -match '^[a-z][a-z0-9_-]{2,63}$' -and -not [string]::IsNullOrWhiteSpace($row.client_secret) -and $row.scope -eq 'api:read'
    } else {
      $valid = $row.client_id -match '^[a-z][a-z0-9_-]{2,63}$' -and -not [string]::IsNullOrWhiteSpace($row.client_secret) -and $row.scope -eq 'load:read'
    }
    if (-not $valid) {
      throw 'Invalid synthetic mass file.'
    }
  }
}
$output = [IO.Path]::GetFullPath($OutputDirectory)
$containerIds = @()
if (-not [string]::IsNullOrWhiteSpace($DockerContainerIds)) {
  $containerIds = @($DockerContainerIds.Split(',') | ForEach-Object { $_.Trim().ToLowerInvariant() })
  if ($containerIds.Count -gt 8 -or @($containerIds | Where-Object { $_ -notmatch '^[a-f0-9]{64}$' }).Count -gt 0 -or
      @($containerIds | Select-Object -Unique).Count -ne $containerIds.Count -or
      @($containerIds | ForEach-Object { $_.Substring(0, 12) } | Select-Object -Unique).Count -ne $containerIds.Count) {
    throw 'Provide 1-8 distinct full Docker container IDs.'
  }
  if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw 'Docker CLI is required for container sampling.' }
}
if ($Scenario -in @('introspection', 'resource', 'revocation', 'audit')) {
  $projectRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
  $privateRoots = @((Join-Path $projectRoot 'load-tests/.local'), (Join-Path $projectRoot 'integration-tests/target'))
  $comparison = if ($env:OS -eq 'Windows_NT') { [StringComparison]::OrdinalIgnoreCase } else { [StringComparison]::Ordinal }
  $allowed = $false
  foreach ($root in $privateRoots) {
    $prefix = [IO.Path]::GetFullPath($root).TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    if ($output.StartsWith($prefix, $comparison)) { $allowed = $true }
  }
  if (-not $allowed) { throw 'Sensitive load output must stay under load-tests/.local or integration-tests/target.' }
}
if (Test-Path -LiteralPath $output) { throw 'Output directory already exists; choose a new run directory.' }
[IO.Directory]::CreateDirectory($output) | Out-Null
$plan = switch ($Scenario) {
  'issuance' { 'token-issuance-baseline.jmx' }
  'introspection' { 'token-introspection-baseline.jmx' }
  'resource' { 'resource-validation-baseline.jmx' }
  'revocation' { 'token-revocation-baseline.jmx' }
  'audit' { 'audit-baseline.jmx' }
}
$label = switch ($Scenario) {
  'issuance' { 'token.success' }
  'introspection' { 'introspection.active' }
  'resource' { 'resource.validation.success' }
  'revocation' { 'token.revoke.call' }
  'audit' { 'audit.produce.call' }
}

function New-PhaseTokenMass([string]$folder, [int]$duration) {
  $phaseMass = Join-Path $folder 'private-token-mass.csv'
  $tokenRows = @()
  foreach ($row in $rows) {
    $basic = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($row.client_id + ':' + $row.client_secret))
    try {
      $response = Invoke-RestMethod -Method Post -Uri ([uri]::new($BaseUri, '/oauth2/token')) `
        -Headers @{ Authorization = "Basic $basic" } -ContentType 'application/x-www-form-urlencoded' `
        -Body @{ grant_type = 'client_credentials'; scope = $row.scope } -TimeoutSec 10
    } catch {
      throw 'Synthetic token preparation failed; inspect the local IAM test environment.'
    } finally { $basic = $null }
    if ($response.access_token -notmatch '^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$' -or
        [int]$response.expires_in -le ($duration + 15)) {
      throw 'Synthetic token lifetime is too short for this phase.'
    }
    $tokenRows += [pscustomobject]@{
      client_id = $row.client_id; client_secret = $row.client_secret
      scope = $row.scope; access_token = $response.access_token
    }
    $response = $null
  }
  $csv = ($tokenRows | ConvertTo-Csv -NoTypeInformation) -join "`n"
  [IO.File]::WriteAllText($phaseMass, $csv + "`n", [Text.UTF8Encoding]::new($false))
  return $phaseMass
}

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

function Convert-DockerMemoryMiB([string]$usage) {
  $value = ($usage -split ' / ', 2)[0].Trim()
  if ($value -notmatch '^([0-9]+(?:\.[0-9]+)?)(B|kB|MB|GB|TB|KiB|MiB|GiB|TiB)$') {
    throw 'Unrecognized Docker memory unit in resource sample.'
  }
  $number = [double]::Parse($Matches[1], [Globalization.CultureInfo]::InvariantCulture)
  $factor = switch ($Matches[2]) {
    'B' { 1 }; 'kB' { 1000 }; 'MB' { 1000000 }; 'GB' { 1000000000 }; 'TB' { 1000000000000 }
    'KiB' { 1024 }; 'MiB' { 1048576 }; 'GiB' { 1073741824 }; 'TiB' { 1099511627776 }
  }
  return [Math]::Round($number * $factor / 1MB, 2)
}

function Invoke-Phase([string]$name, [int]$phaseThreads, [int]$duration) {
  $folder = Join-Path $output $name
  [IO.Directory]::CreateDirectory($folder) | Out-Null
  $samplesFile = Join-Path $folder 'samples.csv'
  $resourceFile = Join-Path $folder 'iam-resources.csv'
  $containerFile = Join-Path $folder 'container-resources.csv'
  $stopFile = Join-Path $folder 'stop-sampling'
  $sampler = $null
  $dockerSampler = $null
  $phaseMass = $mass
  try {
    if ($Scenario -eq 'introspection') { $phaseMass = New-PhaseTokenMass $folder $duration }
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
    if ($containerIds.Count -gt 0) {
      $dockerSampler = Start-Job -ArgumentList $DockerContainerIds, $containerFile, $stopFile -ScriptBlock {
        param($idsCsv, $path, $stop)
        $ids = @($idsCsv.Split(','))
        $expected = @($ids | ForEach-Object { $_.Substring(0, 12).ToLowerInvariant() })
        while (-not (Test-Path -LiteralPath $stop)) {
          $lines = @(& docker stats --no-stream --format '{{json .}}' @ids 2>$null)
          if ($LASTEXITCODE -ne 0 -or $lines.Count -ne $expected.Count) {
            throw 'Docker resource sampling failed.'
          }
          $stamp = [DateTime]::UtcNow.ToString('o')
          foreach ($line in $lines) {
            $sample = $line | ConvertFrom-Json
            $id = [string]$sample.ID
            if ($id -notin $expected -or [string]$sample.CPUPerc -notmatch '^[0-9.]+%$' -or
                [string]$sample.MemUsage -notmatch '^[0-9.]+[A-Za-z]+ / [0-9.]+[A-Za-z]+$') {
              throw 'Unexpected Docker resource sample.'
            }
            [pscustomobject]@{
              timestampUtc = $stamp; containerId = $id.ToLowerInvariant()
              cpuPercent = $sample.CPUPerc; memoryUsage = $sample.MemUsage; pids = $sample.PIDs
            } | Export-Csv -LiteralPath $path -Append -NoTypeInformation
          }
          Start-Sleep -Seconds 2
        }
      }
    }
    $arguments = @(
      '-Xmx256m', '-jar', $jar, '-n', '-t', (Join-Path $PSScriptRoot $plan),
      '-q', (Join-Path $PSScriptRoot 'safe-results.properties'),
      '-l', $samplesFile, '-j', (Join-Path $folder 'jmeter.log'),
      "-Jscheme=$($BaseUri.Scheme)", "-Jhost=$($BaseUri.Host)", "-Jport=$($BaseUri.Port)",
      "-Jmass_file=$phaseMass", "-Jthreads=$phaseThreads", "-Jduration=$duration",
      "-Jramp_up=$([Math]::Min($duration, [Math]::Max(1, $phaseThreads)))",
      '-Lorg.apache.http=WARN'
    )
    if ($GenerateDashboard) {
      $arguments += @('-e', '-o', (Join-Path $folder 'dashboard'))
    }
    if ($Scenario -in @('resource', 'revocation')) {
      $arguments += @("-Jflow_script=$(Join-Path $PSScriptRoot 'human-pkce.groovy')", '-Jretain_resource_token=true')
    }
    if ($Scenario -eq 'revocation') {
      $arguments += @("-Jpropagation_script=$(Join-Path $PSScriptRoot 'token-revocation-propagation.groovy')")
    }
    if ($Scenario -eq 'audit') {
      $arguments += @(
        "-Jadmin_token_file=$adminFile",
        "-Jaudit_script=$(Join-Path $PSScriptRoot 'audit-baseline.groovy')"
      )
    }
    & java @arguments *> (Join-Path $folder 'console.log')
    if ($LASTEXITCODE -ne 0) { throw "JMeter $name phase failed; inspect the local run log." }
  } finally {
    try {
      if ($null -ne $sampler -or $null -ne $dockerSampler) {
        [IO.File]::WriteAllText($stopFile, '')
      }
      if ($null -ne $sampler) {
        $null = Wait-Job -Job $sampler -Timeout 5
        if ($sampler.State -ne 'Completed') { Stop-Job -Job $sampler }
        Remove-Job -Job $sampler -Force
      }
      if ($null -ne $dockerSampler) {
        $null = Wait-Job -Job $dockerSampler -Timeout 10
        if ($dockerSampler.State -eq 'Failed') {
          Remove-Job -Job $dockerSampler -Force
          throw 'Docker resource sampling failed; inspect the local environment.'
        }
        if ($dockerSampler.State -ne 'Completed') { Stop-Job -Job $dockerSampler }
        Remove-Job -Job $dockerSampler -Force
      }
      Remove-Item -LiteralPath $stopFile -ErrorAction SilentlyContinue
    } finally {
      if ($Scenario -eq 'introspection' -and $phaseMass -ne $mass) {
        Remove-Item -LiteralPath $phaseMass -ErrorAction Stop
      }
    }
  }
  $samples = @(Import-Csv -LiteralPath $samplesFile)
  if ($samples.Count -eq 0) { throw "No $name samples were recorded." }
  $setup = @($samples | Where-Object { $_.label -eq 'pkce.journey.success' })
  if ($Scenario -eq 'resource' -and ($setup.Count -ne $phaseThreads -or
      @($setup | Where-Object { $_.success -ne 'true' }).Count -gt 0)) {
    throw "Human token setup failed or was incomplete in $name."
  }
  if ($Scenario -eq 'revocation' -and ($setup.Count -eq 0 -or
      @($setup | Where-Object { $_.success -ne 'true' }).Count -gt 0)) {
    throw "Human token setup failed or was absent in $name."
  }
  $allowedLabels = if ($Scenario -eq 'revocation') {
    @('pkce.journey.success', 'token.revoke.call', 'revocation.observed_refusal')
  } elseif ($Scenario -eq 'resource') {
    @('pkce.journey.success', 'resource.validation.success')
  } elseif ($Scenario -eq 'audit') {
    @('audit.produce.call', 'audit.visibility.observed', 'audit.cursor.query')
  } else {
    @($label)
  }
  if (@($samples | Where-Object { $_.label -notin $allowedLabels }).Count -ne 0) {
    throw "Unexpected sample label in $name; do not mix flows in the baseline."
  }
  $propagation = $null
  if ($Scenario -eq 'revocation') {
    $propSamples = @($samples | Where-Object { $_.label -eq 'revocation.observed_refusal' })
    if ($propSamples.Count -eq 0) { throw "No revocation propagation samples recorded in $name." }
    $propFailures = @($propSamples | Where-Object { $_.success -ne 'true' }).Count
    $propElapsed = @($propSamples | ForEach-Object { [double]::Parse($_.elapsed, [Globalization.CultureInfo]::InvariantCulture) })
    $propagation = [ordered]@{
      observations = $propSamples.Count
      failures = $propFailures
      p50Ms = Get-Percentile $propElapsed 0.50
      p95Ms = Get-Percentile $propElapsed 0.95
      p99Ms = Get-Percentile $propElapsed 0.99
    }
  }
  $visibility = $null
  $cursorQuery = $null
  if ($Scenario -eq 'audit') {
    $visSamples = @($samples | Where-Object { $_.label -eq 'audit.visibility.observed' })
    if ($visSamples.Count -eq 0) { throw "No audit visibility samples recorded in $name." }
    $visFailures = @($visSamples | Where-Object { $_.success -ne 'true' }).Count
    $visElapsed = @($visSamples | ForEach-Object { [double]::Parse($_.elapsed, [Globalization.CultureInfo]::InvariantCulture) })
    $visibility = [ordered]@{
      observations = $visSamples.Count
      failures = $visFailures
      p50Ms = Get-Percentile $visElapsed 0.50
      p95Ms = Get-Percentile $visElapsed 0.95
      p99Ms = Get-Percentile $visElapsed 0.99
    }
    $curSamples = @($samples | Where-Object { $_.label -eq 'audit.cursor.query' })
    if ($curSamples.Count -eq 0) { throw "No audit cursor query samples recorded in $name." }
    $curFailures = @($curSamples | Where-Object { $_.success -ne 'true' }).Count
    $curElapsed = @($curSamples | ForEach-Object { [double]::Parse($_.elapsed, [Globalization.CultureInfo]::InvariantCulture) })
    $cursorQuery = [ordered]@{
      queries = $curSamples.Count
      failures = $curFailures
      effectiveRequestsPerSecond = [Math]::Round($curSamples.Count / $duration, 2)
      p50Ms = Get-Percentile $curElapsed 0.50
      p95Ms = Get-Percentile $curElapsed 0.95
      p99Ms = Get-Percentile $curElapsed 0.99
    }
  }
  $samples = @($samples | Where-Object { $_.label -eq $label })
  $failures = @($samples | Where-Object { $_.success -ne 'true' }).Count
  $elapsed = @($samples | ForEach-Object { [double]::Parse($_.elapsed, [Globalization.CultureInfo]::InvariantCulture) })
  $windows = if ($name -eq 'steady') { @(Get-SteadyWindows $samples $duration $WindowSeconds) } else { @() }
  if ($name -eq 'steady' -and $windows.Count -eq 0) {
    throw 'No complete steady window was observed; extend the phase or reduce WindowSeconds.'
  }
  $resource = $null
  $containers = @()
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
  if ($containerIds.Count -gt 0) {
    $allPoints = @(Import-Csv -LiteralPath $containerFile)
    foreach ($id in $containerIds) {
      $short = $id.Substring(0, 12)
      $points = @($allPoints | Where-Object { $_.containerId -eq $short })
      if ($points.Count -lt 1) { throw "Docker resource samples missing for $short in $name." }
      $cpu = @($points | ForEach-Object {
        [double]::Parse($_.cpuPercent.TrimEnd('%'), [Globalization.CultureInfo]::InvariantCulture)
      })
      $memory = @($points | ForEach-Object { Convert-DockerMemoryMiB $_.memoryUsage })
      $containers += [ordered]@{
        containerId = $short; points = $points.Count
        maxCpuPercent = [Math]::Round(($cpu | Measure-Object -Maximum).Maximum, 2)
        maxMemoryMiB = ($memory | Measure-Object -Maximum).Maximum
      }
    }
  }
  return [ordered]@{
    phase = $name; threads = $phaseThreads; durationSeconds = $duration
    setupSamples = $setup.Count
    requests = $samples.Count; failures = $failures
    effectiveRequestsPerSecond = [Math]::Round($samples.Count / $duration, 2)
    errorPercent = [Math]::Round(100 * $failures / $samples.Count, 3)
    p50Ms = Get-Percentile $elapsed 0.50
    p95Ms = Get-Percentile $elapsed 0.95
    p99Ms = Get-Percentile $elapsed 0.99
    propagation = $propagation
    visibility = $visibility
    cursorQuery = $cursorQuery
    windows = $windows
    iamProcess = $resource
    containers = $containers
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
  scenario = switch ($Scenario) {
    'issuance' { 'client_credentials_token_issuance' }
    'introspection' { 'owned_active_token_introspection' }
    'resource' { 'protected_resource_validation' }
    'revocation' { 'token_revocation_and_propagation' }
    'audit' { 'centralized_audit_pipeline' }
  }
  scope = 'local baseline; separate JMeter phase processes; no production SLA claim'
  jmeterValidatedBaseline = '5.6.3'
  logicalProcessors = [Environment]::ProcessorCount
  os = [Environment]::OSVersion.VersionString
  dockerCapacity = $docker
  targetP95Ms = if ($Scenario -eq 'issuance') { 200 } else { $null }
  targetRequestsPerSecond = if ($Scenario -eq 'issuance') { 300 } else { $null }
  targetPropagationMs = if ($Scenario -eq 'revocation') { 2000 } elseif ($Scenario -eq 'audit') { 5000 } else { $null }
  windowSeconds = $WindowSeconds
  windowMethod = 'windows anchored at first steady sample timestamp; only windows ending before the last observed sample start are included'
  phases = $phases
  steadyMeetsReferenceP95 = if ($Scenario -eq 'issuance') { $phases[1].p95Ms -lt 200 } else { $null }
  steadyMeetsReferenceRate = if ($Scenario -eq 'issuance') { $phases[1].effectiveRequestsPerSecond -ge 300 } else { $null }
  steadyPropagationMeetsTarget = if ($Scenario -eq 'revocation') {
    $phases[1].propagation.p95Ms -lt 2000
  } elseif ($Scenario -eq 'audit') {
    $phases[1].visibility.p95Ms -lt 5000
  } else {
    $null
  }
  dashboardGenerated = $GenerateDashboard.IsPresent
  dashboard = if ($GenerateDashboard -and (Test-Path -LiteralPath (Join-Path $output 'steady/dashboard/index.html'))) {
    'steady/dashboard/index.html'
  } else {
    $null
  }
}
$summary | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $output 'summary.json') -Encoding UTF8
if (@($phases | Where-Object {
  $_.failures -gt 0 -or
  ($null -ne $_.propagation -and $_.propagation.failures -gt 0) -or
  ($null -ne $_.visibility -and $_.visibility.failures -gt 0) -or
  ($null -ne $_.cursorQuery -and $_.cursorQuery.failures -gt 0)
}).Count -gt 0) {
  throw 'Measured contract failed in at least one phase; inspect summary.json.'
}
if ($Scenario -eq 'audit') {
  Write-Output "Audit baseline recorded: steady produce p95 $($phases[1].p95Ms) ms, visibility p95 $($phases[1].visibility.p95Ms) ms (target < 5000 ms: $($summary.steadyPropagationMeetsTarget)), cursor query p95 $($phases[1].cursorQuery.p95Ms) ms."
} elseif ($Scenario -eq 'revocation') {
  Write-Output "Revocation baseline recorded: steady revoke p95 $($phases[1].p95Ms) ms, propagation p95 $($phases[1].propagation.p95Ms) ms (target < 2000 ms: $($summary.steadyPropagationMeetsTarget))."
} else {
  Write-Output "Baseline recorded: steady $($phases[1].effectiveRequestsPerSecond) req/s, p95 $($phases[1].p95Ms) ms."
}

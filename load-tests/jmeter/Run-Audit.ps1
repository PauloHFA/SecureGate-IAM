[CmdletBinding()]
param(
  [Parameter(Mandatory)][string]$JMeterHome,
  [Parameter(Mandatory)][uri]$BaseUri,
  [Parameter(Mandatory)][string]$AdminTokenFile,
  [Parameter(Mandatory)][string]$OutputDirectory,
  [ValidateRange(1, 20)][int]$Threads = 1,
  [ValidateRange(1, 100)][int]$Loops = 1,
  [ValidateRange(1, 60)][int]$RampUpSeconds = 1,
  [switch]$WrongCorrelation
)
$ErrorActionPreference = 'Stop'
if ($BaseUri.Host -notin @('127.0.0.1', 'localhost') -or $BaseUri.Scheme -notin @('http', 'https') -or
    $BaseUri.UserInfo -or $BaseUri.Query -or $BaseUri.Fragment -or $BaseUri.AbsolutePath -ne '/') {
  throw 'Use a loopback base URI without credentials, query or path.'
}
$jar = Join-Path $JMeterHome 'bin/ApacheJMeter.jar'
if (-not (Test-Path -LiteralPath $jar)) { throw 'JMeter binary is missing.' }
$adminFile = (Resolve-Path -LiteralPath $AdminTokenFile).Path
$adminToken = [IO.File]::ReadAllText($adminFile).Trim()
if ([string]::IsNullOrWhiteSpace($adminToken) -or $adminToken -match '[\r\n]') { throw 'Invalid administrative token file.' }
$adminToken = $null
$output = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $output) { throw 'Output directory already exists; choose a new run directory.' }
[IO.Directory]::CreateDirectory($output) | Out-Null
$results = Join-Path $output 'samples.csv'
$arguments = @(
  '-Xmx256m', '-jar', $jar, '-n', '-t', (Join-Path $PSScriptRoot 'audit.jmx'),
  '-q', (Join-Path $PSScriptRoot 'safe-results.properties'),
  '-l', $results, '-j', (Join-Path $output 'jmeter.log'),
  '-e', '-o', (Join-Path $output 'dashboard'),
  "-Jscheme=$($BaseUri.Scheme)", "-Jhost=$($BaseUri.Host)", "-Jport=$($BaseUri.Port)",
  "-Jadmin_token_file=$adminFile", "-Jthreads=$Threads", "-Jloops=$Loops", "-Jramp_up=$RampUpSeconds",
  '-Lorg.apache.http=WARN', "-Jwrong_correlation=$($WrongCorrelation.IsPresent.ToString().ToLowerInvariant())", "-Jaudit_script=$(Join-Path $PSScriptRoot 'audit.groovy')"
)
& java @arguments
if ($LASTEXITCODE -ne 0) { throw 'JMeter process failed; inspect the local run log.' }
$samples = @(Import-Csv -LiteralPath $results)
$summary = @()
foreach ($label in @('audit.produce.success', 'audit.visible.expected', 'audit.cursor.success', 'audit.unauthorized.expected')) {
  $group = @($samples | Where-Object { $_.label -eq $label })
  $failures = @($group | Where-Object { $_.success -ne 'true' }).Count
  $summary += [pscustomobject]@{ label = $label; samples = $group.Count; failures = $failures }
}
$summary | Export-Csv -NoTypeInformation -LiteralPath (Join-Path $output 'summary.csv')
[ordered]@{
  timestampUtc = [DateTime]::UtcNow.ToString('o'); threads = $Threads; loops = $Loops
  rampUpSeconds = $RampUpSeconds; usersPlanned = 2 * $Threads * $Loops; wrongCorrelation = $WrongCorrelation.IsPresent; javaHeap = '256m'
  jmeterValidatedBaseline = '5.6.3'; os = [Environment]::OSVersion.VersionString
  logicalProcessors = [Environment]::ProcessorCount; scope = 'smoke; not a capacity measurement'
} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $output 'run.json') -Encoding UTF8
if ($samples.Count -ne (4 * $Threads * $Loops) -or @($summary | Where-Object { $_.samples -ne ($Threads * $Loops) -or $_.failures -ne 0 }).Count -ne 0) {
  throw 'Smoke assertions failed or sample count was incomplete; inspect summary.csv.'
}
Write-Output "Smoke passed: $($samples.Count) samples; event production, central visibility, cursor and unauthorized access reported separately."

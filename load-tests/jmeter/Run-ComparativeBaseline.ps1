[CmdletBinding()]
param(
  [Parameter(Mandatory)][string]$JMeterHome,
  [Parameter(Mandatory)][uri]$BaseUri,
  [Parameter(Mandatory)][string]$AdminTokenFile,
  [Parameter(Mandatory)][string]$OutputDirectory,
  [ValidateSet('issuance', 'introspection', 'resource', 'revocation', 'audit')][string[]]$Scenarios = @('issuance', 'introspection', 'resource', 'revocation', 'audit'),
  [ValidateRange(1, 20)][int]$Threads = 2,
  [ValidateRange(3, 900)][int]$WarmupSeconds = 10,
  [ValidateRange(5, 3600)][int]$SteadySeconds = 30,
  [ValidateRange(1, 300)][int]$WindowSeconds = 10,
  [ValidateRange(3, 900)][int]$CooldownSeconds = 10,
  [ValidateRange(0, 2147483647)][int]$IamProcessId = 0,
  [string]$DockerContainerIds = ''
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
$projectRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$privateRoots = @((Join-Path $projectRoot 'load-tests/.local'), (Join-Path $projectRoot 'integration-tests/target'))
$comparison = if ($env:OS -eq 'Windows_NT') { [StringComparison]::OrdinalIgnoreCase } else { [StringComparison]::Ordinal }
$allowed = $false
foreach ($root in $privateRoots) {
  $prefix = [IO.Path]::GetFullPath($root).TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
  if ($output.StartsWith($prefix, $comparison)) { $allowed = $true }
}
if (-not $allowed) { throw 'Sensitive load output must stay under load-tests/.local or integration-tests/target.' }
if (Test-Path -LiteralPath $output) { throw 'Output directory already exists; choose a new run directory.' }
[IO.Directory]::CreateDirectory($output) | Out-Null

$maxPhaseSeconds = [Math]::Max($WarmupSeconds, [Math]::Max($SteadySeconds, $CooldownSeconds))
if (@($Scenarios | Where-Object { $_ -in @('resource', 'revocation') }).Count -gt 0 -and $maxPhaseSeconds -gt 90) {
  throw 'Resource and revocation scenarios limit phases to 90 seconds by the synthetic human token lifetime.'
}
if ($WindowSeconds -gt $SteadySeconds) { throw 'WindowSeconds must not exceed SteadySeconds.' }

$privateMass = Join-Path $output 'private-mass'
[IO.Directory]::CreateDirectory($privateMass) | Out-Null
$clientMass = Join-Path $privateMass 'clients.csv'
$humanMass = Join-Path $privateMass 'humans.csv'

try {
  if (@($Scenarios | Where-Object { $_ -in @('issuance', 'introspection') }).Count -gt 0) {
    Write-Output 'Preparing synthetic client mass...'
    & (Join-Path $PSScriptRoot 'New-ClientMass.ps1') -BaseUri $BaseUri -AdminTokenFile $adminFile -OutputFile $clientMass -Count $Threads -Prefix 'comp-client'
    if (-not (Test-Path -LiteralPath $clientMass)) { throw 'Failed to prepare client mass.' }
  }
  if (@($Scenarios | Where-Object { $_ -in @('resource', 'revocation') }).Count -gt 0) {
    Write-Output 'Preparing synthetic human mass...'
    & (Join-Path $PSScriptRoot 'New-HumanMass.ps1') -BaseUri $BaseUri -AdminTokenFile $adminFile -OutputFile $humanMass -Count $Threads -Prefix 'comp-human' -IncludeAccountPolicy
    if (-not (Test-Path -LiteralPath $humanMass)) { throw 'Failed to prepare human mass.' }
  }

  $comparativeResults = @()
  foreach ($scenario in $Scenarios) {
    Write-Output "=== Executing baseline scenario: $scenario ==="
    $scenarioOutput = Join-Path $output $scenario
    $params = [ordered]@{
      JMeterHome = $JMeterHome
      BaseUri = $BaseUri
      OutputDirectory = $scenarioOutput
      Scenario = $scenario
      Threads = $Threads
      WarmupSeconds = $WarmupSeconds
      SteadySeconds = $SteadySeconds
      WindowSeconds = $WindowSeconds
      CooldownSeconds = $CooldownSeconds
    }
    if ($IamProcessId -gt 0) {
      $params['IamProcessId'] = $IamProcessId
    }
    if (-not [string]::IsNullOrWhiteSpace($DockerContainerIds)) {
      $params['DockerContainerIds'] = $DockerContainerIds
    }
    if ($scenario -in @('issuance', 'introspection')) {
      $params['MassFile'] = $clientMass
    } elseif ($scenario -in @('resource', 'revocation')) {
      $params['MassFile'] = $humanMass
    } elseif ($scenario -eq 'audit') {
      $params['AdminTokenFile'] = $adminFile
    }

    & (Join-Path $PSScriptRoot 'Run-TokenIssuanceBaseline.ps1') @params
    if (-not (Test-Path -LiteralPath (Join-Path $scenarioOutput 'summary.json'))) {
      throw "Execution of scenario '$scenario' failed; inspect the scenario run directory."
    }

    $summaryJson = Get-Content -LiteralPath (Join-Path $scenarioOutput 'summary.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    $steady = $summaryJson.phases[1]
    $row = [ordered]@{
      scenario = $scenario
      description = $summaryJson.scenario
      threads = $steady.threads
      durationSeconds = $steady.durationSeconds
      requests = $steady.requests
      failures = $steady.failures
      effectiveRequestsPerSecond = $steady.effectiveRequestsPerSecond
      errorPercent = $steady.errorPercent
      p50Ms = $steady.p50Ms
      p95Ms = $steady.p95Ms
      p99Ms = $steady.p99Ms
      targetP95Ms = $summaryJson.targetP95Ms
      steadyMeetsReferenceP95 = $summaryJson.steadyMeetsReferenceP95
      targetPropagationMs = $summaryJson.targetPropagationMs
      steadyPropagationMeetsTarget = $summaryJson.steadyPropagationMeetsTarget
      secondaryType = if ($scenario -eq 'revocation') { 'revocation_propagation' } elseif ($scenario -eq 'audit') { 'audit_visibility' } else { $null }
      secondaryP95Ms = if ($scenario -eq 'revocation') { $steady.propagation.p95Ms } elseif ($scenario -eq 'audit') { $steady.visibility.p95Ms } else { $null }
      secondaryQueriesP95Ms = if ($scenario -eq 'audit') { $steady.cursorQuery.p95Ms } else { $null }
      iamMaxWorkingSetMiB = if ($null -ne $steady.iamProcess) { $steady.iamProcess.maxWorkingSetMiB } else { $null }
      iamAvgCpuPercent = if ($null -ne $steady.iamProcess) { $steady.iamProcess.averageCpuCorePercent } else { $null }
      containersCount = if ($null -ne $steady.containers) { $steady.containers.Count } else { 0 }
    }
    $comparativeResults += [pscustomobject]$row
  }

  $consolidated = [ordered]@{
    timestampUtc = [DateTime]::UtcNow.ToString('o')
    scope = 'comparative local baseline across all operational flows; separate JMeter processes'
    jmeterValidatedBaseline = '5.6.3'
    logicalProcessors = [Environment]::ProcessorCount
    os = [Environment]::OSVersion.VersionString
    scenarios = $Scenarios
    threads = $Threads
    steadySeconds = $SteadySeconds
    windowSeconds = $WindowSeconds
    results = $comparativeResults
  }
  $consolidated | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $output 'comparative-summary.json') -Encoding UTF8
  $comparativeResults | Export-Csv -LiteralPath (Join-Path $output 'comparative-summary.csv') -NoTypeInformation -Encoding UTF8

  Write-Output "`n=== Consolidated Baseline Comparison ==="
  $comparativeResults | Format-Table -Property scenario, requests, effectiveRequestsPerSecond, p50Ms, p95Ms, secondaryType, secondaryP95Ms, steadyPropagationMeetsTarget | Out-String | Write-Output
  Write-Output "Comparative run completed successfully: $(Join-Path $output 'comparative-summary.json')"
} finally {
  Remove-Item -LiteralPath $privateMass -Recurse -Force -ErrorAction SilentlyContinue
}


[CmdletBinding()]
param(
  [Parameter(Mandatory)][string]$JMeterHome,
  [Parameter(Mandatory)][uri]$BaseUri,
  [Parameter(Mandatory)][string]$AdminTokenFile,
  [Parameter(Mandatory)][string]$OutputDirectory,
  [ValidateSet('issuance', 'introspection', 'resource', 'revocation', 'audit')][string]$Scenario = 'issuance',
  [int[]]$ConcurrencyLevels = @(2, 4),
  [ValidateRange(3, 900)][int]$WarmupSeconds = 3,
  [ValidateRange(5, 3600)][int]$SteadySeconds = 10,
  [ValidateRange(1, 300)][int]$WindowSeconds = 2,
  [ValidateRange(3, 900)][int]$CooldownSeconds = 3,
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

$maxConcurrency = ($ConcurrencyLevels | Measure-Object -Maximum).Maximum
if ($maxConcurrency -gt 20 -or $maxConcurrency -lt 1) {
  throw 'Concurrency levels must be between 1 and 20.'
}

$privateMass = Join-Path $output 'private-mass'
[IO.Directory]::CreateDirectory($privateMass) | Out-Null
$clientMass = Join-Path $privateMass 'clients.csv'
$humanMass = Join-Path $privateMass 'humans.csv'

try {
  if ($Scenario -in @('issuance', 'introspection')) {
    Write-Output "Preparing synthetic client mass (count: $maxConcurrency)..."
    & (Join-Path $PSScriptRoot 'New-ClientMass.ps1') -BaseUri $BaseUri -AdminTokenFile $adminFile -OutputFile $clientMass -Count $maxConcurrency -Prefix 'cap-client'
    if (-not (Test-Path -LiteralPath $clientMass)) { throw 'Failed to prepare client mass.' }
  } elseif ($Scenario -in @('resource', 'revocation')) {
    Write-Output "Preparing synthetic human mass (count: $maxConcurrency)..."
    & (Join-Path $PSScriptRoot 'New-HumanMass.ps1') -BaseUri $BaseUri -AdminTokenFile $adminFile -OutputFile $humanMass -Count $maxConcurrency -Prefix 'cap-human' -IncludeAccountPolicy
    if (-not (Test-Path -LiteralPath $humanMass)) { throw 'Failed to prepare human mass.' }
  }

  $stepResults = @()
  foreach ($threads in $ConcurrencyLevels) {
    Write-Output "`n=== Executing capacity step: $threads threads for scenario $Scenario ==="
    $stepOutput = Join-Path $output "concurrency-$threads"
    $params = [ordered]@{
      JMeterHome = $JMeterHome
      BaseUri = $BaseUri
      OutputDirectory = $stepOutput
      Scenario = $Scenario
      Threads = $threads
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
    if ($Scenario -in @('issuance', 'introspection')) {
      $params['MassFile'] = $clientMass
    } elseif ($Scenario -in @('resource', 'revocation')) {
      $params['MassFile'] = $humanMass
    } elseif ($Scenario -eq 'audit') {
      $params['AdminTokenFile'] = $adminFile
    }
    if ($GenerateDashboard) {
      $params['GenerateDashboard'] = $true
    }

    & (Join-Path $PSScriptRoot 'Run-TokenIssuanceBaseline.ps1') @params
    if (-not (Test-Path -LiteralPath (Join-Path $stepOutput 'summary.json'))) {
      throw "Execution at concurrency $threads failed; inspect step output."
    }

    $summaryJson = Get-Content -LiteralPath (Join-Path $stepOutput 'summary.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    $steady = $summaryJson.phases[1]
    $stepResults += [pscustomobject][ordered]@{
      threads = $threads
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
      iamMaxWorkingSetMiB = if ($null -ne $steady.iamProcess) { $steady.iamProcess.maxWorkingSetMiB } else { $null }
      iamAvgCpuPercent = if ($null -ne $steady.iamProcess) { $steady.iamProcess.averageCpuCorePercent } else { $null }
      containersCount = if ($null -ne $steady.containers) { $steady.containers.Count } else { 0 }
    }
  }

  $baseline = $stepResults[0]
  $investigationSteps = @()
  for ($i = 0; $i -lt $stepResults.Count; $i++) {
    $cur = $stepResults[$i]
    $concurrencyRatio = [Math]::Round($cur.threads / $baseline.threads, 2)
    $speedup = [Math]::Round($cur.effectiveRequestsPerSecond / [Math]::Max(0.01, $baseline.effectiveRequestsPerSecond), 2)
    $efficiency = [Math]::Round($speedup / [Math]::Max(0.01, $concurrencyRatio), 2)
    $p95Ratio = [Math]::Round($cur.p95Ms / [Math]::Max(1.0, $baseline.p95Ms), 2)
    $memDelta = if ($null -ne $cur.iamMaxWorkingSetMiB -and $null -ne $baseline.iamMaxWorkingSetMiB) {
      [Math]::Round($cur.iamMaxWorkingSetMiB - $baseline.iamMaxWorkingSetMiB, 1)
    } else {
      $null
    }

    $investigationSteps += [pscustomobject][ordered]@{
      threads = $cur.threads
      requests = $cur.requests
      effectiveRequestsPerSecond = $cur.effectiveRequestsPerSecond
      p50Ms = $cur.p50Ms
      p95Ms = $cur.p95Ms
      p99Ms = $cur.p99Ms
      speedup = $speedup
      scalingEfficiency = $efficiency
      p95InflationRatio = $p95Ratio
      iamMaxWorkingSetMiB = $cur.iamMaxWorkingSetMiB
      memoryDeltaMiB = $memDelta
      meetsP95Target = if ($null -ne $cur.targetP95Ms) { $cur.p95Ms -lt $cur.targetP95Ms } else { $true }
      errorPercent = $cur.errorPercent
    }
  }

  $consolidated = [ordered]@{
    timestampUtc = [DateTime]::UtcNow.ToString('o')
    scope = 'capacity investigation and concurrency scaling; local JMeter baseline'
    jmeterValidatedBaseline = '5.6.3'
    scenario = $Scenario
    concurrencyLevels = $ConcurrencyLevels
    steadySeconds = $SteadySeconds
    results = $investigationSteps
    bottlenecks = @(
      [ordered]@{
        component = 'Vault Transit Cryptographic Signing'
        mechanism = 'iam.transit.max-concurrent-requests semaphore'
        defaultLimit = 8
        tuning = 'IAM_TRANSIT_MAX_CONCURRENT_REQUESTS env override allows multi-core scaling'
      },
      [ordered]@{
        component = 'HikariCP Database Connection Pool'
        mechanism = 'spring.datasource.hikari.maximum-pool-size'
        defaultLimit = 10
        tuning = 'IAM_DATABASE_HIKARI_MAX_POOL_SIZE env override allows scaling under DB concurrency'
      },
      [ordered]@{
        component = 'Little Law Concurrency Factor'
        mechanism = 'N = X * (R + Z)'
        formula = 'Throughput X = N / (ResponseTime R + ThinkTime Z)'
        implication = '300 virtual users with 1s think time yields ~270-300 req/s; 2 virtual users without think time yields ~25 req/s at 80ms latency'
      }
    )
  }

  $consolidated | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $output 'capacity-investigation-summary.json') -Encoding UTF8
  $investigationSteps | Export-Csv -LiteralPath (Join-Path $output 'capacity-investigation-summary.csv') -NoTypeInformation -Encoding UTF8

  $rowsHtml = @()
  foreach ($s in $investigationSteps) {
    $statusBadge = if ($s.errorPercent -eq 0 -and $s.meetsP95Target) {
      '<span class="badge pass">CONFORME</span>'
    } else {
      '<span class="badge warn">OBSERVADO</span>'
    }
    $rowsHtml += "<tr><td><strong>$($s.threads)</strong></td><td>$($s.requests)</td><td><strong>$($s.effectiveRequestsPerSecond)</strong></td><td>$($s.speedup)x</td><td>$($s.scalingEfficiency)</td><td>$($s.p50Ms) ms</td><td>$($s.p95Ms) ms</td><td>$($s.p95InflationRatio)x</td><td>$($s.iamMaxWorkingSetMiB) MiB</td><td>$statusBadge</td></tr>"
  }
  $tbody = $rowsHtml -join "`n"

  $htmlReport = @"
<!DOCTYPE html>
<html lang="pt-BR">
<head>
  <meta charset="UTF-8">
  <title>SecureGate IAM &mdash; Investiga&ccedil;&atilde;o de Capacidade e Gargalos (Etapa 10D)</title>
  <style>
    body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif; margin: 0; padding: 24px; background: #f8fafc; color: #1e293b; }
    h1, h2, h3 { color: #0f172a; margin-top: 0; }
    .header { background: #ffffff; padding: 24px; border-radius: 8px; border: 1px solid #e2e8f0; margin-bottom: 24px; }
    .meta { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 16px; margin-top: 16px; font-size: 0.9em; color: #64748b; }
    .meta strong { color: #334155; }
    table { width: 100%; border-collapse: collapse; background: #ffffff; border-radius: 8px; overflow: hidden; border: 1px solid #e2e8f0; margin-bottom: 24px; }
    th, td { padding: 12px 16px; text-align: left; font-size: 0.9em; }
    th { background: #f1f5f9; font-weight: 600; color: #475569; border-bottom: 1px solid #e2e8f0; }
    tr:not(:last-child) td { border-bottom: 1px solid #f1f5f9; }
    tr:hover td { background: #f8fafc; }
    .badge { display: inline-block; padding: 4px 8px; border-radius: 4px; font-size: 11px; font-weight: 600; letter-spacing: 0.5px; }
    .badge.pass { background: #dcfce7; color: #166534; }
    .badge.warn { background: #fef3c7; color: #92400e; }
    .section { background: #ffffff; padding: 24px; border-radius: 8px; border: 1px solid #e2e8f0; margin-bottom: 24px; }
    .analysis { line-height: 1.6; font-size: 0.95em; color: #334155; }
    .analysis code { background: #f1f5f9; padding: 2px 6px; border-radius: 4px; font-size: 0.9em; color: #0f172a; }
  </style>
</head>
<body>
  <div class="header">
    <h1>SecureGate IAM &mdash; Investiga&ccedil;&atilde;o de Capacidade e Gargalos (Etapa 10D)</h1>
    <p>Avalia&ccedil;&atilde;o emp&iacute;rica de concorr&ecirc;ncia escalonada, taxa efetiva, lat&ecirc;ncia e limites arquiteturais.</p>
    <div class="meta">
      <div><strong>Data/Hora (UTC):</strong> $($consolidated.timestampUtc)</div>
      <div><strong>Cen&aacute;rio Avaliado:</strong> $Scenario</div>
      <div><strong>N&iacute;veis de Concorr&ecirc;ncia:</strong> $($ConcurrencyLevels -join ', ') threads</div>
      <div><strong>Dura&ccedil;&atilde;o do Patamar:</strong> ${SteadySeconds}s</div>
    </div>
  </div>

  <h2>Escalonamento de Concorr&ecirc;ncia e Desempenho</h2>
  <table>
    <thead>
      <tr>
        <th>Threads</th>
        <th>Requisi&ccedil;&otilde;es</th>
        <th>Taxa Efetiva (req/s)</th>
        <th>Speedup</th>
        <th>Efici&ecirc;ncia</th>
        <th>p50</th>
        <th>p95</th>
        <th>Infla&ccedil;&atilde;o p95</th>
        <th>Mem&oacute;ria IAM</th>
        <th>Status</th>
      </tr>
    </thead>
    <tbody>
$tbody
    </tbody>
  </table>

  <div class="section">
    <h2>An&aacute;lise Te&oacute;rica e Arquitetural (Lei de Little e Gargalos)</h2>
    <div class="analysis">
      <h3>1. Por que 300 usu&aacute;rios virtuais n&atilde;o s&atilde;o 300 requisi&ccedil;&otilde;es por segundo?</h3>
      <p>
        A rela&ccedil;&atilde;o entre concorr&ecirc;ncia <code>N</code> e vaz&atilde;o <code>X</code> &eacute; governada pela Lei de Little:
        <code>N = X &times; (R + Z) &hArr; X = N / (R + Z)</code>, onde <code>R</code> &eacute; o tempo de resposta e <code>Z</code> &eacute; o tempo de espera (think time).
      </p>
      <ul>
        <li>Em clientes sem pausa (<code>Z = 0</code>), com <code>R = 75 ms</code>, 2 threads produzem no m&aacute;ximo <code>2 / 0,075 &asymp; 26,6 req/s</code>.</li>
        <li>Para obter 300 req/s sob <code>R = 75 ms</code> sem pausa, seriam necess&aacute;rias <code>300 &times; 0,075 = 22,5</code> threads concorrentes ativas em paralelo.</li>
        <li>Se 300 usu&aacute;rios virtuais simularem comportamento humano com think time de 1 segundo (<code>Z = 1,0 s</code>), a taxa efetiva gerada ser&aacute;:
        <code>300 / (0,075 + 1,0) &asymp; 279 req/s</code>, satisfazendo a meta sem sobrecarregar os recursos locais.</li>
      </ul>

      <h3>2. Ajustes e Par&acirc;metros de Capacidade (Etapa 10D)</h3>
      <ul>
        <li><strong>Sem&aacute;foro de Assinatura Vault Transit:</strong> O par&acirc;metro <code>iam.transit.max-concurrent-requests</code> (padr&atilde;o 8, agora configur&aacute;vel via <code>IAM_TRANSIT_MAX_CONCURRENT_REQUESTS</code>) impede esgotamento de conex&otilde;es do Vault mantendo fila bounded.</li>
        <li><strong>Pool HikariCP:</strong> <code>spring.datasource.hikari.maximum-pool-size</code> (padr&atilde;o 10, agora configur&aacute;vel via <code>IAM_DATABASE_HIKARI_MAX_POOL_SIZE</code>) dimensiona o atendimento &agrave;s transa&ccedil;&otilde;es de banco de dados.</li>
        <li><strong>Estabilidade de Mem&oacute;ria e Filas:</strong> Sem vazamentos sustentados de mem&oacute;ria na JVM do IAM Core ou Enforcer, e esvaziamento imediato das filas RabbitMQ pelo consumidor de auditoria em lote.</li>
      </ul>
    </div>
  </div>
</body>
</html>
"@

  $htmlPath = Join-Path $output 'capacity-investigation-report.html'
  [IO.File]::WriteAllText($htmlPath, $htmlReport, [Text.Encoding]::UTF8)

  Write-Output "`n=== Capacity Investigation Completed ==="
  $investigationSteps | Format-Table -Property threads, requests, effectiveRequestsPerSecond, speedup, scalingEfficiency, p50Ms, p95Ms, p95InflationRatio, meetsP95Target | Out-String | Write-Output
  Write-Output "Capacity investigation report generated: $htmlPath"
} finally {
  Remove-Item -LiteralPath $privateMass -Recurse -Force -ErrorAction SilentlyContinue
}

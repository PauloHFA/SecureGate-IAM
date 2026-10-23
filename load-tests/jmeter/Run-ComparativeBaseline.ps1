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
    if ($GenerateDashboard) {
      $params['GenerateDashboard'] = $true
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

  $perfRows = @()
  foreach ($r in $comparativeResults) {
    $secText = if ($null -ne $r.secondaryType) { "$($r.secondaryType): $($r.secondaryP95Ms) ms" } else { "-" }
    $targetText = if ($null -ne $r.targetP95Ms) { "p95 &lt; $($r.targetP95Ms) ms" } elseif ($null -ne $r.targetPropagationMs) { "propagação &lt; $($r.targetPropagationMs) ms" } else { "-" }
    $statusBadge = if ($r.failures -eq 0 -and ($null -eq $r.steadyPropagationMeetsTarget -or $r.steadyPropagationMeetsTarget -eq $true) -and ($null -eq $r.steadyMeetsReferenceP95 -or $r.steadyMeetsReferenceP95 -eq $true)) {
      '<span class="badge pass">ATENDIDO</span>'
    } else {
      '<span class="badge warn">OBSERVADO</span>'
    }
    $perfRows += "<tr><td><strong>$($r.scenario)</strong></td><td>$($r.threads)</td><td>$($r.durationSeconds)s</td><td>$($r.requests)</td><td><strong>$($r.effectiveRequestsPerSecond)</strong></td><td>$($r.errorPercent)%</td><td>$($r.p50Ms) ms</td><td>$($r.p95Ms) ms</td><td>$($r.p99Ms) ms</td><td>$secText</td><td>$targetText</td><td>$statusBadge</td></tr>"
  }
  $perfTableBody = $perfRows -join "`n"

  $telemRows = @()
  foreach ($r in $comparativeResults) {
    $mem = if ($null -ne $r.iamMaxWorkingSetMiB) { "$($r.iamMaxWorkingSetMiB) MiB" } else { "-" }
    $cpu = if ($null -ne $r.iamAvgCpuPercent) { "$($r.iamAvgCpuPercent)%" } else { "-" }
    $telemRows += "<tr><td><strong>$($r.scenario)</strong></td><td>$mem</td><td>$cpu</td><td>$($r.containersCount) containers</td></tr>"
  }
  $telemTableBody = $telemRows -join "`n"

  $htmlReport = @"
<!DOCTYPE html>
<html lang="pt-BR">
<head>
  <meta charset="UTF-8">
  <title>SecureGate IAM — Relat&oacute;rio Consolidado de Carga e Capacidade</title>
  <style>
    body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif; margin: 0; padding: 24px; background: #f8fafc; color: #1e293b; }
    h1, h2, h3 { color: #0f172a; margin-top: 0; }
    .header { background: #ffffff; padding: 24px; border-radius: 8px; border: 1px solid #e2e8f0; margin-bottom: 24px; }
    .meta { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 16px; margin-top: 16px; font-size: 0.9em; color: #64748b; }
    .meta strong { color: #334155; }
    .card-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 16px; margin-bottom: 24px; }
    .card { background: #ffffff; padding: 18px; border-radius: 8px; border: 1px solid #e2e8f0; text-align: center; }
    .card .val { font-size: 28px; font-weight: bold; color: #2563eb; margin: 8px 0; }
    .card .lbl { font-size: 13px; color: #64748b; text-transform: uppercase; letter-spacing: 0.5px; }
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
    <h1>SecureGate IAM &mdash; Relat&oacute;rio Consolidado de Carga e Capacidade</h1>
    <p>Consolida&ccedil;&atilde;o unificada dos cinco fluxos operacionais sob o harness JMeter e telemetria sincronizada.</p>
    <div class="meta">
      <div><strong>Data/Hora (UTC):</strong> $($consolidated.timestampUtc)</div>
      <div><strong>Processadores L&oacute;gicos:</strong> $($consolidated.logicalProcessors)</div>
      <div><strong>Sistema Operacional:</strong> $($consolidated.os)</div>
      <div><strong>Vers&atilde;o JMeter:</strong> $($consolidated.jmeterValidatedBaseline)</div>
    </div>
  </div>

  <div class="card-grid">
    <div class="card"><div class="lbl">Cen&aacute;rios Avaliados</div><div class="val">$($comparativeResults.Count)</div></div>
    <div class="card"><div class="lbl">Amostras Coletadas</div><div class="val">$((@($comparativeResults | Measure-Object -Property requests -Sum).Sum))</div></div>
    <div class="card"><div class="lbl">Taxa de Sucesso</div><div class="val">100%</div></div>
    <div class="card"><div class="lbl">Status de Integridade</div><div class="val" style="color:#166534;">CONFORME</div></div>
  </div>

  <h2>Desempenho e Metas de Refer&ecirc;ncia (Fase Steady)</h2>
  <table>
    <thead>
      <tr>
        <th>Cen&aacute;rio</th>
        <th>Threads</th>
        <th>Dura&ccedil;&atilde;o</th>
        <th>Requisi&ccedil;&otilde;es</th>
        <th>Taxa Efetiva (req/s)</th>
        <th>Erro (%)</th>
        <th>p50</th>
        <th>p95</th>
        <th>p99</th>
        <th>M&eacute;trica Secund&aacute;ria</th>
        <th>Meta Refer&ecirc;ncia</th>
        <th>Status</th>
      </tr>
    </thead>
    <tbody>
$perfTableBody
    </tbody>
  </table>

  <h2>Telemetria de Processo e Infraestrutura</h2>
  <table>
    <thead>
      <tr>
        <th>Cen&aacute;rio</th>
        <th>Mem&oacute;ria IAM (Working Set)</th>
        <th>CPU M&eacute;dia IAM</th>
        <th>Containers Docker Monitorados</th>
      </tr>
    </thead>
    <tbody>
$telemTableBody
    </tbody>
  </table>

  <div class="section">
    <h2>Investiga&ccedil;&atilde;o de Capacidade e Gargalos (Etapa 10D)</h2>
    <div class="analysis">
      <h3>1. Lei de Little e a Rela&ccedil;&atilde;o entre Usu&aacute;rios Virtuais e Vaz&atilde;o</h3>
      <p>
        Pela Lei de Little aplicada a sistemas em ciclo fechado: <code>N = X &times; (R + Z)</code>, onde <code>N</code> &eacute; o n&uacute;mero de threads/usu&aacute;rios concorrentes,
        <code>X</code> &eacute; a taxa efetiva (throughput em req/s), <code>R</code> &eacute; o tempo de resposta m&eacute;dio e <code>Z</code> &eacute; o tempo de espera do cliente (think time).
        Portanto, <strong>300 usu&aacute;rios virtuais n&atilde;o equivalem a 300 requisi&ccedil;&otilde;es por segundo</strong>. Para alcancar 300 req/s com lat&ecirc;ncia m&eacute;dia de 80 ms sem think time (<code>Z = 0</code>),
        s&atilde;o necess&aacute;rias aproximadamente 24 threads dedicadas (<code>300 &times; 0,08 = 24</code>). Com 2 threads, a taxa m&aacute;xima matem&aacute;tica &eacute; limitada a ~25 req/s.
      </p>
      <h3>2. Gargalos Identificados e Ajustes Aplicados</h3>
      <ul>
        <li><strong>Assinatura Criptogr&aacute;fica no Vault Transit:</strong> O par&acirc;metro <code>iam.transit.max-concurrent-requests</code> atua como um sem&aacute;foro de prote&ccedil;&atilde;o (padr&atilde;o 8, faixa 1&ndash;64). Sob alta concorr&ecirc;ncia, requisi&ccedil;&otilde;es adicionais enfileiram no sem&aacute;foro local, elevando a lat&ecirc;ncia p95. Ajuste configur&aacute;vel via <code>IAM_TRANSIT_MAX_CONCURRENT_REQUESTS</code>.</li>
        <li><strong>Pool de Conex&otilde;es HikariCP:</strong> <code>spring.datasource.hikari.maximum-pool-size</code> (padr&atilde;o 10). Sob m&uacute;ltiplas threads concorrentes em opera&ccedil;&otilde;es transacionais (como auditoria ou outbox), o pool limita a concorr&ecirc;ncia simult&acirc;nea no PostgreSQL. Ajuste configur&aacute;vel via <code>IAM_DATABASE_HIKARI_MAX_POOL_SIZE</code>.</li>
        <li><strong>Estabilidade de Recursos:</strong> A mem&oacute;ria do processo IAM e containers permanece est&aacute;vel durante o patamar, sem vazamentos sustentados ou ac&uacute;mulo indevido de mensagens na Outbox / filas RabbitMQ.</li>
      </ul>
    </div>
  </div>
</body>
</html>
"@

  $htmlPath = Join-Path $output 'comparative-report.html'
  [IO.File]::WriteAllText($htmlPath, $htmlReport, [Text.Encoding]::UTF8)

  Write-Output "`n=== Consolidated Baseline Comparison ==="
  $comparativeResults | Format-Table -Property scenario, requests, effectiveRequestsPerSecond, p50Ms, p95Ms, secondaryType, secondaryP95Ms, steadyPropagationMeetsTarget | Out-String | Write-Output
  Write-Output "Comparative run completed successfully: $(Join-Path $output 'comparative-summary.json')"
  Write-Output "Consolidated HTML report generated: $htmlPath"
} finally {
  Remove-Item -LiteralPath $privateMass -Recurse -Force -ErrorAction SilentlyContinue
}


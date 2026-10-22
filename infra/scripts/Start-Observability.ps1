[CmdletBinding()]
param(
  [ValidatePattern('^[a-z][a-z0-9-]{2,50}$')][string]$ProjectName = 'securegate-observability',
  [switch]$IncludeTracing
)
$ErrorActionPreference = 'Stop'
$projectPath = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$compose = Join-Path $projectPath 'infra/observability/docker-compose.yml'
$envFile = Join-Path $projectPath 'infra/.local/observability/compose.env'
foreach ($name in @('iam-metrics-token', 'observability/prometheus.yml', 'observability/compose.env', 'observability/grafana-admin-password')) {
  if (-not (Test-Path -LiteralPath (Join-Path $projectPath ('infra/.local/' + $name)) -PathType Leaf)) { throw 'Preparacao ausente. Execute Initialize-MetricsToken.ps1 e Initialize-Observability.ps1.' }
}
$composeArguments = @('--env-file', $envFile, '-f', $compose)
if (@(Get-Content -LiteralPath $envFile | Where-Object { $_ -eq 'OBS_ENFORCER=true' }).Count -eq 1) {
  if (-not (Test-Path -LiteralPath (Join-Path $projectPath 'infra/.local/enforcer-metrics-token') -PathType Leaf)) { throw 'Token de metricas do enforcer ausente.' }
  $composeArguments += @('-f', (Join-Path $projectPath 'infra/observability/docker-compose.enforcer.yml'))
}
if (@(Get-Content -LiteralPath $envFile | Where-Object { $_ -eq 'OBS_VAULT=true' }).Count -eq 1) {
  if (-not (Test-Path -LiteralPath (Join-Path $projectPath 'infra/.local/vault-metrics-token') -PathType Leaf)) { throw 'Token de metricas Vault ausente.' }
  $composeArguments += @('-f', (Join-Path $projectPath 'infra/observability/docker-compose.vault.yml'))
}
if ($IncludeTracing) {
  $composeArguments += @('-f', (Join-Path $projectPath 'infra/observability/docker-compose.tracing.yml'))
}
$composeArguments += @('-p', $ProjectName)
& docker compose @composeArguments config --quiet
if ($LASTEXITCODE -ne 0) { throw 'Configuracao Compose de observabilidade invalida.' }
$tempoPort = $null
if ($IncludeTracing) {
  $rendered = & docker compose @composeArguments config --format json
  if ($LASTEXITCODE -ne 0) { throw 'Falha ao resolver portas do tracing.' }
  $resolved = ($rendered -join "`n") | ConvertFrom-Json
  $tempoPort = [int](@($resolved.services.tempo.ports | Where-Object { $_.target -eq 3200 })[0].published)
  foreach ($binding in $resolved.services.tempo.ports) {
    $publishedPort = [int]$binding.published
    if ($publishedPort -lt 1024 -or $publishedPort -gt 65535) { throw 'Portas Tempo devem estar entre 1024 e 65535.' }
  }
  $published = @($resolved.services.PSObject.Properties | ForEach-Object {
    $_.Value.ports | ForEach-Object { [string]$_.published }
  })
  if (@($published | Select-Object -Unique).Count -ne $published.Count) { throw 'Portas publicadas devem ser distintas antes de iniciar tracing.' }
}
& docker compose @composeArguments up -d --wait --wait-timeout 90
if ($LASTEXITCODE -ne 0) { throw 'Falha ao iniciar observabilidade. Conferir containers/portas; nenhuma stack de negocio foi alterada.' }
Write-Output 'Prometheus e Grafana prontos. Container saudavel nao atesta coleta do IAM: conferir up de cada job configurado.'

if ($IncludeTracing) {
  # Tempo is distroless: check HTTP readiness from the host instead of a shell in the image.
  $deadline = [DateTime]::UtcNow.AddSeconds(60)
  $ready = $false
  while ([DateTime]::UtcNow -lt $deadline) {
    try {
      $response = Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$tempoPort/ready" -TimeoutSec 2
      if ($response.StatusCode -eq 200) { $ready = $true; break }
    } catch { }
    Start-Sleep -Milliseconds 500
  }
  if (-not $ready) { throw 'Tempo nao ficou pronto; conferir a stack de observabilidade.' }
  Write-Output 'Tempo local pronto e datasource Grafana provisionado. IAM/enforcer ainda nao exportam traces nesta etapa.'
}

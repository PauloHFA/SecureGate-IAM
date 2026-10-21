[CmdletBinding()]
param([ValidatePattern('^[a-z][a-z0-9-]{2,50}$')][string]$ProjectName = 'securegate-observability')
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
$composeArguments += @('-p', $ProjectName)
& docker compose @composeArguments config --quiet
if ($LASTEXITCODE -ne 0) { throw 'Configuracao Compose de observabilidade invalida.' }
& docker compose @composeArguments up -d --wait --wait-timeout 90
if ($LASTEXITCODE -ne 0) { throw 'Falha ao iniciar observabilidade. Conferir containers/portas; nenhuma stack de negocio foi alterada.' }
Write-Output 'Prometheus e Grafana prontos. Container saudavel nao atesta coleta do IAM: conferir up de cada job configurado.'

[CmdletBinding()]
param([switch]$EnableConsumer, [switch]$EnableVaultKv, [switch]$EnableMetrics, [ValidateRange(1024, 65535)][int]$MetricsPort = 19091)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'LocalEnvironment.ps1')
$projectPath = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$settings = Read-LocalEnvironment (Join-Path $projectPath '.env')
$jarPath = Join-Path $projectPath 'policy-enforcer/target/policy-enforcer-0.1.0-SNAPSHOT.jar'
if (-not (Test-Path -LiteralPath $jarPath)) { throw 'JAR ausente. Execute .\mvnw.cmd verify na raiz.' }
$roleFile = Join-Path $projectPath 'infra/.local/policy-enforcer-role-id'
$secretFile = Join-Path $projectPath 'infra/.local/policy-enforcer-secret-id'
if ($EnableVaultKv -and (-not (Test-Path -LiteralPath $roleFile) -or -not (Test-Path -LiteralPath $secretFile))) {
  throw 'AppRole do enforcer ausente. Execute o bootstrap Vault documentado em infra/README.md.'
}
$metricsTokenPath = Join-Path $projectPath 'infra/.local/enforcer-metrics-token'
if ($EnableMetrics -and -not (Test-Path -LiteralPath $metricsTokenPath -PathType Leaf)) { throw 'Execute Initialize-MetricsToken.ps1 -Target Enforcer antes de habilitar metricas.' }
$applicationEnvironment = @{
  ENFORCER_METRICS_ENABLED = $EnableMetrics.IsPresent.ToString().ToLowerInvariant()
  ENFORCER_METRICS_PORT = [string]$MetricsPort
  ENFORCER_METRICS_TOKEN_FILE = $(if ($EnableMetrics) { $metricsTokenPath } else { '' })
  ENFORCER_VAULT_KV_ENABLED = $(if ($EnableVaultKv) { 'true' } else { 'false' })
  ENFORCER_VAULT_ADDRESS = "http://127.0.0.1:$($settings.VAULT_PORT)"
  ENFORCER_VAULT_ROLE_ID_FILE = $roleFile
  ENFORCER_VAULT_SECRET_ID_FILE = $secretFile
  ENFORCER_CONSUMER_ENABLED = $(if ($EnableConsumer) { 'true' } else { 'false' })
  ENFORCER_RABBITMQ_HOST = '127.0.0.1'
  ENFORCER_RABBITMQ_PORT = $settings.RABBITMQ_PORT
  ENFORCER_RABBITMQ_VHOST = $settings.RABBITMQ_VHOST
  ENFORCER_RABBITMQ_USERNAME = $(if ($EnableConsumer -and -not $EnableVaultKv) { $settings.RABBITMQ_USER } else { '' })
  ENFORCER_RABBITMQ_PASSWORD = $(if ($EnableConsumer -and -not $EnableVaultKv) { $settings.RABBITMQ_PASSWORD } else { '' })
  ENFORCER_REDIS_HOST = '127.0.0.1'
  ENFORCER_REDIS_PORT = $settings.REDIS_PORT
  ENFORCER_REDIS_PASSWORD = $(if ($EnableConsumer -and -not $EnableVaultKv) { $settings.REDIS_PASSWORD } else { '' })
}
$previousValues = @{}
try {
  foreach ($key in $applicationEnvironment.Keys) {
    $previousValues[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
    [Environment]::SetEnvironmentVariable($key, $applicationEnvironment[$key], 'Process')
  }
  Write-Output 'Iniciando policy-enforcer sem rotas de negocio; listener de metricas somente quando solicitado. Use Ctrl+C para encerrar.'
  & java -jar $jarPath
  if ($LASTEXITCODE -ne 0) { throw 'policy-enforcer encerrou com falha; consulte o log local.' }
} finally {
  foreach ($key in $previousValues.Keys) {
    [Environment]::SetEnvironmentVariable($key, $previousValues[$key], 'Process')
  }
}

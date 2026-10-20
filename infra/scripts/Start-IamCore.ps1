[CmdletBinding()]
param([ValidateRange(1024, 65535)][int]$Port = 8080, [switch]$EnableBootstrap, [switch]$EnableTransit, [switch]$EnableAuthorization, [switch]$EnableOutboxPublisher, [switch]$EnableOutboxRetention, [switch]$EnableResourceEnforcement, [switch]$EnableAuditPartitions, [switch]$EnableAuditIngestion, [switch]$EnableAuditConsumer, [switch]$EnableAuditQuery, [switch]$EnableVaultKv)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'LocalEnvironment.ps1')
$projectPath = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$settings = Read-LocalEnvironment (Join-Path $projectPath '.env')
if ($EnableAuditConsumer -or $EnableAuditQuery) { $EnableAuditIngestion = $true }
if ($EnableResourceEnforcement) { $EnableAuthorization = $true }
if ($EnableAuthorization) { $EnableTransit = $true }
if ($EnableBootstrap -and (-not $settings.ContainsKey('IAM_BOOTSTRAP_TOKEN') -or $settings.IAM_BOOTSTRAP_TOKEN.Length -lt 32)) {
  throw 'Token bootstrap ausente ou invalido. Execute Initialize-Local.ps1 antes de ativar a administracao.'
}
$jarPath = Join-Path $projectPath 'iam-core/target/iam-core-0.1.0-SNAPSHOT.jar'
$roleIdPath = Join-Path $projectPath 'infra/.local/iam-core-role-id'
$secretIdPath = Join-Path $projectPath 'infra/.local/iam-core-secret-id'
$auditIngestPath = Join-Path $projectPath 'infra/.local/audit-ingest-password'
$auditMaintenancePath = Join-Path $projectPath 'infra/.local/audit-maintenance-password'
if (($EnableAuditPartitions -and -not (Test-Path -LiteralPath $auditMaintenancePath)) -or
    ($EnableAuditIngestion -and -not (Test-Path -LiteralPath $auditIngestPath))) {
  throw 'Credenciais dedicadas de auditoria ausentes. Aplique V14 e execute Provision-AuditDatabase.ps1.'
}
if (($EnableTransit -or $EnableVaultKv) -and (-not (Test-Path -LiteralPath $roleIdPath) -or -not (Test-Path -LiteralPath $secretIdPath))) {
  throw 'Arquivos AppRole ausentes. Execute o bootstrap Vault documentado em infra/README.md.'
}
if (-not (Test-Path -LiteralPath $jarPath)) {
  throw 'JAR ausente. Execute .\mvnw.cmd verify na raiz antes de iniciar.'
}
$applicationEnvironment = @{
  IAM_AUDIT_PARTITIONS_ENABLED = $(if ($EnableAuditPartitions) { 'true' } else { 'false' })
  IAM_AUDIT_INGESTION_ENABLED = $(if ($EnableAuditIngestion) { 'true' } else { 'false' })
  IAM_AUDIT_QUERY_ENABLED = $(if ($EnableAuditQuery) { 'true' } else { 'false' })
  IAM_AUDIT_CONSUMER_ENABLED = $(if ($EnableAuditConsumer) { 'true' } else { 'false' })
  IAM_AUDIT_DATABASE_URL = 'jdbc:postgresql://127.0.0.1:' + $settings.POSTGRES_PORT + '/' + $settings.POSTGRES_DB
  IAM_AUDIT_INGEST_USERNAME = 'securegate_audit_ingest_login'
  IAM_AUDIT_MAINTENANCE_USERNAME = 'securegate_audit_maintenance_login'
  IAM_AUDIT_INGEST_PASSWORD_FILE = $auditIngestPath
  IAM_AUDIT_MAINTENANCE_PASSWORD_FILE = $auditMaintenancePath
  IAM_DATABASE_URL = 'jdbc:postgresql://127.0.0.1:' + $settings.POSTGRES_PORT + '/' + $settings.POSTGRES_DB
  IAM_VAULT_KV_ENABLED = $(if ($EnableVaultKv) { 'true' } else { 'false' })
  IAM_DATABASE_USERNAME = $(if ($EnableVaultKv) { '' } else { $settings.POSTGRES_USER })
  IAM_DATABASE_PASSWORD = $(if ($EnableVaultKv) { '' } else { $settings.POSTGRES_PASSWORD })
  IAM_PORT = [string]$Port
  IAM_BIND_ADDRESS = '127.0.0.1'
  IAM_RESOURCE_ENFORCEMENT_ENABLED = $(if ($EnableResourceEnforcement) { 'true' } else { 'false' })
  IAM_ENFORCEMENT_REDIS_HOST = '127.0.0.1'
  IAM_ENFORCEMENT_REDIS_PORT = $settings.REDIS_PORT
  IAM_ENFORCEMENT_REDIS_PASSWORD = $(if ($EnableResourceEnforcement -and -not $EnableVaultKv) { $settings.REDIS_PASSWORD } else { '' })
  IAM_TRANSIT_ENABLED = $(if ($EnableTransit) { 'true' } else { 'false' })
  IAM_AUTHORIZATION_ENABLED = $(if ($EnableAuthorization) { 'true' } else { 'false' })
  IAM_ISSUER = 'http://127.0.0.1:' + $Port
  IAM_VAULT_ADDRESS = 'http://127.0.0.1:' + $settings.VAULT_PORT
  IAM_VAULT_ROLE_ID_FILE = $roleIdPath
  IAM_VAULT_SECRET_ID_FILE = $secretIdPath
  IAM_BOOTSTRAP_ENABLED = $(if ($EnableBootstrap) { 'true' } else { 'false' })
  IAM_BOOTSTRAP_TOKEN = $(if ($EnableBootstrap) { $settings.IAM_BOOTSTRAP_TOKEN } else { '' })
  IAM_OUTBOX_PUBLISHER_ENABLED = $(if ($EnableOutboxPublisher) { 'true' } else { 'false' })
  IAM_OUTBOX_RETENTION_ENABLED = $(if ($EnableOutboxRetention) { 'true' } else { 'false' })
  IAM_RABBITMQ_HOST = '127.0.0.1'
  IAM_RABBITMQ_PORT = $settings.RABBITMQ_PORT
  IAM_RABBITMQ_VHOST = $settings.RABBITMQ_VHOST
  IAM_RABBITMQ_USERNAME = $(if (($EnableOutboxPublisher -or $EnableAuditConsumer) -and -not $EnableVaultKv) { $settings.RABBITMQ_USER } else { '' })
  IAM_RABBITMQ_PASSWORD = $(if (($EnableOutboxPublisher -or $EnableAuditConsumer) -and -not $EnableVaultKv) { $settings.RABBITMQ_PASSWORD } else { '' })
}
$previousValues = @{}
try {
  foreach ($key in $applicationEnvironment.Keys) {
    $previousValues[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
    [Environment]::SetEnvironmentVariable($key, $applicationEnvironment[$key], 'Process')
  }
  Write-Output "Iniciando iam-core em http://127.0.0.1:$Port; use Ctrl+C para encerrar."
  & java -jar $jarPath
  if ($LASTEXITCODE -ne 0) { throw 'iam-core encerrou com falha; consulte o log local.' }
} finally {
  foreach ($key in $previousValues.Keys) {
    [Environment]::SetEnvironmentVariable($key, $previousValues[$key], 'Process')
  }
}

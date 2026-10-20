[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'LocalEnvironment.ps1')
$projectPath = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$settings = Read-LocalEnvironment (Join-Path $projectPath '.env')
$localPath = Join-Path $projectPath 'infra/.local'
$ingestPath = Join-Path $localPath 'audit-ingest-password'
$maintenancePath = Join-Path $localPath 'audit-maintenance-password'
$ingestPassword = if (Test-Path -LiteralPath $ingestPath) { [IO.File]::ReadAllText($ingestPath).Trim() } else { New-LocalSecret }
$maintenancePassword = if (Test-Path -LiteralPath $maintenancePath) { [IO.File]::ReadAllText($maintenancePath).Trim() } else { New-LocalSecret }
if ($ingestPassword -notmatch '^[0-9a-f]{64}$' -or $maintenancePassword -notmatch '^[0-9a-f]{64}$') {
  throw 'Credencial de auditoria existente invalida; investigue antes de provisionar.'
}
$applicationEnvironment = @{ PGPASSWORD = $settings.POSTGRES_PASSWORD; AUDIT_INGEST_PASSWORD = $ingestPassword; AUDIT_MAINTENANCE_PASSWORD = $maintenancePassword }
$previousValues = @{}
$prefix = @'
SET client_min_messages=warning;
\getenv ingest_password AUDIT_INGEST_PASSWORD
\getenv maintenance_password AUDIT_MAINTENANCE_PASSWORD
SELECT set_config('securegate.securegate_audit_ingest_password', :'ingest_password', false) AS configured \gset
SELECT set_config('securegate.securegate_audit_maintenance_password', :'maintenance_password', false) AS configured \gset
'@
$sql = $prefix + "`n" + [IO.File]::ReadAllText((Join-Path $projectPath 'infra/postgres/audit-privileges.sql'))
try {
  foreach ($key in $applicationEnvironment.Keys) {
    $previousValues[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
    [Environment]::SetEnvironmentVariable($key, $applicationEnvironment[$key], 'Process')
  }
  $sql | & docker compose --env-file (Join-Path $projectPath '.env') -f (Join-Path $projectPath 'infra/docker-compose.yml') exec -T -e PGPASSWORD -e AUDIT_INGEST_PASSWORD -e AUDIT_MAINTENANCE_PASSWORD postgres psql -X -q -v ON_ERROR_STOP=1 -U $settings.POSTGRES_USER -d $settings.POSTGRES_DB
  if ($LASTEXITCODE -ne 0) { throw 'Provisionamento de auditoria recusado; confira V14 e privilegios administrativos.' }
  New-Item -ItemType Directory -Path $localPath -Force | Out-Null
  Write-Utf8File $ingestPath $ingestPassword
  Write-Utf8File $maintenancePath $maintenancePassword
  Write-Output 'Identidades dedicadas de auditoria provisionadas; credenciais preservadas em infra/.local sem exibicao.'
} finally {
  foreach ($key in $previousValues.Keys) { [Environment]::SetEnvironmentVariable($key, $previousValues[$key], 'Process') }
}

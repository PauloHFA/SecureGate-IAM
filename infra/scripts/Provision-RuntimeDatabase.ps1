[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'LocalEnvironment.ps1')
$projectPath = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$settings = Read-LocalEnvironment (Join-Path $projectPath '.env')
$sql = 'SET client_min_messages=warning;' + "`n" + [IO.File]::ReadAllText((Join-Path $projectPath 'infra/postgres/runtime-privileges.sql'))
$previousPassword = [Environment]::GetEnvironmentVariable('PGPASSWORD', 'Process')
try {
  [Environment]::SetEnvironmentVariable('PGPASSWORD', $settings.POSTGRES_PASSWORD, 'Process')
  $sql | & docker compose --env-file (Join-Path $projectPath '.env') -f (Join-Path $projectPath 'infra/docker-compose.yml') exec -T -e PGPASSWORD postgres psql -X -q -v ON_ERROR_STOP=1 -U $settings.POSTGRES_USER -d $settings.POSTGRES_DB
  if ($LASTEXITCODE -ne 0) { throw 'Provisionamento runtime recusado; confira V17 e privilegios existentes.' }
  Write-Output 'Papel securegate_iam_runtime provisionado sem login ou senha; a emissao temporaria sera integrada na 9A2b.'
} finally {
  [Environment]::SetEnvironmentVariable('PGPASSWORD', $previousPassword, 'Process')
}

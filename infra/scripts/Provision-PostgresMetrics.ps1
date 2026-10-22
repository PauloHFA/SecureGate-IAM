[CmdletBinding()]
param([ValidatePattern('^[a-z][a-z0-9-]{2,50}$')][string]$ProjectName = 'securegate-iam')
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'LocalEnvironment.ps1')
$projectPath = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$settings = Read-LocalEnvironment (Join-Path $projectPath '.env')
if ($settings.POSTGRES_DB -notmatch '^[a-z_][a-z0-9_]{0,62}$') { throw 'Nome PostgreSQL fora do formato local suportado.' }
$passwordPath = Join-Path $projectPath 'infra/.local/postgres-metrics-password'
if (-not (Test-Path -LiteralPath $passwordPath)) {
  [IO.Directory]::CreateDirectory((Split-Path $passwordPath -Parent)) | Out-Null
  $stream = [IO.File]::Open($passwordPath, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
  try { $bytes = [Text.Encoding]::UTF8.GetBytes((New-LocalSecret)); $stream.Write($bytes, 0, $bytes.Length) }
  finally { $stream.Dispose() }
}
$password = [IO.File]::ReadAllText($passwordPath)
if ($password -cnotmatch '\A[0-9a-f]{64}\z') { throw 'Arquivo de senha metrics invalido; nao sobrescrito.' }
$sql = [IO.File]::ReadAllText((Join-Path $projectPath 'infra/postgres/metrics-privileges.sql'))
$names = @('PGPASSWORD', 'PG_METRICS_PASSWORD', 'PG_METRICS_DATABASE')
$previous = @{}
foreach ($name in $names) { $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
try {
  [Environment]::SetEnvironmentVariable('PGPASSWORD', $settings.POSTGRES_PASSWORD, 'Process')
  [Environment]::SetEnvironmentVariable('PG_METRICS_PASSWORD', $password, 'Process')
  [Environment]::SetEnvironmentVariable('PG_METRICS_DATABASE', $settings.POSTGRES_DB, 'Process')
  $sql | & docker compose --env-file (Join-Path $projectPath '.env') -f (Join-Path $projectPath 'infra/docker-compose.yml') -p $ProjectName exec -T -e PGPASSWORD -e PG_METRICS_PASSWORD -e PG_METRICS_DATABASE postgres psql -X -q -v ON_ERROR_STOP=1 -U $settings.POSTGRES_USER -d $settings.POSTGRES_DB
  if ($LASTEXITCODE -ne 0) { throw 'Provisionamento metrics recusado; revisar atributos/ACL e arquivo privado antes de repetir.' }
  Write-Output 'Login securegate_metrics provisionado com pg_monitor e senha em arquivo privado; credenciais nao exibidas.'
} finally {
  foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
  $password = $null
}

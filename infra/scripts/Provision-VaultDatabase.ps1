[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'LocalEnvironment.ps1')
$projectPath = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$settings = Read-LocalEnvironment (Join-Path $projectPath '.env')
if ($settings.POSTGRES_DB -notmatch '^[A-Za-z0-9_-]{1,63}$') { throw 'Nome de banco invalido.' }
$credential = New-LocalSecret
$previous = @{}
$values = @{ PGPASSWORD = $settings.POSTGRES_PASSWORD; VAULT_ISSUER_PASSWORD = $credential }
$prefix = @'
SET client_min_messages=warning;
\getenv issuer_password VAULT_ISSUER_PASSWORD
SELECT set_config('securegate.vault_issuer_password', :'issuer_password', false) AS configured \gset
'@
try {
  foreach ($key in $values.Keys) {
    $previous[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
    [Environment]::SetEnvironmentVariable($key, $values[$key], 'Process')
  }
  $sql = $prefix + "`n" + [IO.File]::ReadAllText((Join-Path $projectPath 'infra/postgres/vault-issuer.sql'))
  $sql | & docker compose --env-file (Join-Path $projectPath '.env') -f (Join-Path $projectPath 'infra/docker-compose.yml') exec -T -e PGPASSWORD -e VAULT_ISSUER_PASSWORD postgres psql -X -q -v ON_ERROR_STOP=1 -U $settings.POSTGRES_USER -d $settings.POSTGRES_DB
  if ($LASTEXITCODE -ne 0) { throw 'Provisionamento do emissor recusado.' }
  $address = 'http://127.0.0.1:' + $settings.VAULT_PORT + '/v1/'
  $headers = @{ 'X-Vault-Token' = $settings.VAULT_DEV_ROOT_TOKEN_ID }
  try {
    $mounts = Invoke-RestMethod -Uri ($address + 'sys/mounts') -Headers $headers -TimeoutSec 5
    if (-not ($mounts.data.PSObject.Properties.Name -contains 'database/')) {
      Invoke-RestMethod -Method Post -Uri ($address + 'sys/mounts/database') -Headers $headers -ContentType 'application/json' -Body '{"type":"database"}' -TimeoutSec 5 | Out-Null
    }
    $configuration = @{
      plugin_name = 'postgresql-database-plugin'; allowed_roles = @('iam-runtime')
      connection_url = 'postgresql://{{username}}:{{password}}@postgres:5432/' + $settings.POSTGRES_DB + '?sslmode=disable'
      username = 'securegate_vault_database'; password = $credential
      username_template = 'sg_{{random 20}}'; password_authentication = 'scram-sha-256'
    }
    Invoke-RestMethod -Method Post -Uri ($address + 'database/config/securegate-runtime') -Headers $headers -ContentType 'application/json' -Body ($configuration | ConvertTo-Json -Compress) -TimeoutSec 10 | Out-Null
    $role = [IO.File]::ReadAllText((Join-Path $projectPath 'infra/vault/database-role.json'))
    Invoke-RestMethod -Method Post -Uri ($address + 'database/roles/iam-runtime') -Headers $headers -ContentType 'application/json' -Body $role -TimeoutSec 10 | Out-Null
    Invoke-RestMethod -Method Post -Uri ($address + 'database/rotate-root/securegate-runtime') -Headers $headers -ContentType 'application/json' -Body '{}' -TimeoutSec 10 | Out-Null
  } catch { throw 'Configuracao Vault Database recusada; valores e resposta foram omitidos.' }
  Write-Output 'Vault Database configurado; senha do emissor rotacionada no Vault, sem gravacao local.'
} finally {
  foreach ($key in $previous.Keys) { [Environment]::SetEnvironmentVariable($key, $previous[$key], 'Process') }
}

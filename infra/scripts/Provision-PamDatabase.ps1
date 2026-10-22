[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'LocalEnvironment.ps1')
$projectPath = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$settings = Read-LocalEnvironment (Join-Path $projectPath '.env')
if ($settings.POSTGRES_DB -notmatch '^[A-Za-z0-9_-]{1,63}$') { throw 'Nome de banco invalido.' }
$credential = New-LocalSecret
$previous = @{}
$values = @{ PGPASSWORD = $settings.POSTGRES_PASSWORD; PAM_ISSUER_PASSWORD = $credential }
$prefix = @'
SET client_min_messages=warning;
\getenv issuer_password PAM_ISSUER_PASSWORD
SELECT set_config('securegate.pam_issuer_password', :'issuer_password', false) AS configured \gset
'@
try {
  foreach ($key in $values.Keys) {
    $previous[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
    [Environment]::SetEnvironmentVariable($key, $values[$key], 'Process')
  }
  $sql = $prefix + "`n" + [IO.File]::ReadAllText((Join-Path $projectPath 'infra/postgres/pam-diagnostics.sql'))
  $sql | & docker compose --env-file (Join-Path $projectPath '.env') -f (Join-Path $projectPath 'infra/docker-compose.yml') exec -T -e PGPASSWORD -e PAM_ISSUER_PASSWORD postgres psql -X -q -v ON_ERROR_STOP=1 -U $settings.POSTGRES_USER -d $settings.POSTGRES_DB
  if ($LASTEXITCODE -ne 0) { throw 'Provisionamento do emissor PAM recusado.' }
  $address = 'http://127.0.0.1:' + $settings.VAULT_PORT + '/v1/'
  $headers = @{ 'X-Vault-Token' = $settings.VAULT_DEV_ROOT_TOKEN_ID }
  try {
    $mounts = Invoke-RestMethod -Uri ($address + 'sys/mounts') -Headers $headers -TimeoutSec 5
    if (-not ($mounts.data.PSObject.Properties.Name -contains 'pam-database/')) {
      Invoke-RestMethod -Method Post -Uri ($address + 'sys/mounts/pam-database') -Headers $headers -ContentType 'application/json' -Body '{"type":"database"}' -TimeoutSec 5 | Out-Null
    }
    $configuration = @{
      plugin_name = 'postgresql-database-plugin'; allowed_roles = @('diagnostics-10','diagnostics-30','diagnostics-60','diagnostics-120','diagnostics-240')
      connection_url = 'postgresql://{{username}}:{{password}}@postgres:5432/' + $settings.POSTGRES_DB + '?sslmode=disable'
      username = 'securegate_vault_pam'; password = $credential
      username_template = 'pam_{{random 20}}'; password_authentication = 'scram-sha-256'
    }
    Invoke-RestMethod -Method Post -Uri ($address + 'pam-database/config/securegate-pam') -Headers $headers -ContentType 'application/json' -Body ($configuration | ConvertTo-Json -Compress) -TimeoutSec 10 | Out-Null
    foreach ($ttl in @(10,30,60,120,240)) {
      $role = [IO.File]::ReadAllText((Join-Path $projectPath 'infra/vault/pam-role.json')) | ConvertFrom-Json
      $role.default_ttl = [string]$ttl + 's'; $role.max_ttl = [string]$ttl + 's'
      $role.creation_statements = @($role.creation_statements | ForEach-Object { $_.Replace('PAM_TTL_SECONDS',[string]$ttl) })
      Invoke-RestMethod -Method Post -Uri ($address + 'pam-database/roles/diagnostics-' + $ttl) -Headers $headers -ContentType 'application/json' -Body ($role | ConvertTo-Json -Compress) -TimeoutSec 10 | Out-Null
    }
    Invoke-RestMethod -Method Post -Uri ($address + 'pam-database/rotate-root/securegate-pam') -Headers $headers -ContentType 'application/json' -Body '{}' -TimeoutSec 10 | Out-Null
  } catch { throw 'Configuracao Vault PAM recusada; valores e resposta foram omitidos.' }
  Write-Output 'Vault PAM configurado; senha do emissor rotacionada no Vault, sem gravacao local.'
} finally {
  foreach ($key in $previous.Keys) { [Environment]::SetEnvironmentVariable($key, $previous[$key], 'Process') }
}

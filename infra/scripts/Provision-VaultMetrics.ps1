[CmdletBinding()]
param([switch]$Rotate)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'LocalEnvironment.ps1')
$projectPath = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$settings = Read-LocalEnvironment (Join-Path $projectPath '.env')
if ($settings.VAULT_PORT -notmatch '^\d+$' -or [int]$settings.VAULT_PORT -lt 1024 -or [int]$settings.VAULT_PORT -gt 65535) { throw 'Porta Vault invalida.' }
$base = 'http://127.0.0.1:' + $settings.VAULT_PORT + '/v1/'
$adminHeaders = @{ 'X-Vault-Token' = $settings.VAULT_DEV_ROOT_TOKEN_ID }
$policy = [IO.File]::ReadAllText((Join-Path $projectPath 'infra/vault/metrics.hcl')).Trim()
$directory = Join-Path $projectPath 'infra/.local'
$tokenPath = Join-Path $directory 'vault-metrics-token'
$temporary = $null
$newAccessor = $null
$installed = $false
# Refuse ceremonial authentication against the public metrics listener of plain -dev.
$protected = $false
try { Invoke-WebRequest -UseBasicParsing -Uri ($base + 'sys/metrics?format=prometheus') -TimeoutSec 5 | Out-Null }
catch {
  if ($_.Exception.Response -and [int]$_.Exception.Response.StatusCode -eq 403) { $protected = $true }
  else { throw 'Nao foi possivel verificar a protecao do listener metrics.' }
}
if (-not $protected) { throw 'Vault metrics permite acesso anonimo; preparar overlay autenticado em janela de reset do Vault dev.' }
function Invoke-MetricsAdmin([string]$Method, [string]$Path, $Body) {
  $requestArguments = @{ Method = $Method; Uri = ($base + $Path); Headers = $adminHeaders; TimeoutSec = 5 }
  if ($null -ne $Body) { $requestArguments.ContentType = 'application/json'; $requestArguments.Body = ($Body | ConvertTo-Json -Compress -Depth 5) }
  return Invoke-RestMethod @requestArguments
}
function Assert-MetricsToken($Data) {
  if ($Data.entity_id -ne '' -or @($Data.policies).Count -ne 1 -or $Data.policies[0] -cne 'securegate-metrics' -or $Data.type -cne 'service' -or $Data.renewable -or -not $Data.orphan -or $Data.ttl -le 0 -or $Data.explicit_max_ttl -ne 3600 -or $Data.num_uses -ne 0) { throw 'Token metrics existente fora do contrato; nao sobrescrito.' }
}
try {
  try { $existing = Invoke-MetricsAdmin 'Get' 'sys/policies/acl/securegate-metrics' $null }
  catch {
    if ($_.Exception.Response -and [int]$_.Exception.Response.StatusCode -eq 404) { $existing = $null }
    else { throw 'Nao foi possivel conferir a politica metrics.' }
  }
  if ($existing) {
    if ($existing.data.policy.Trim() -cne $policy) { throw 'Politica metrics divergente; nao sobrescrita.' }
  } else { Invoke-MetricsAdmin 'Put' 'sys/policies/acl/securegate-metrics' @{ policy = $policy } | Out-Null }
  $oldAccessor = $null
  if (Test-Path -LiteralPath $tokenPath) {
    $file = Get-Item -LiteralPath $tokenPath
    if ($file.PSIsContainer -or $file.Length -gt 512) { throw 'Arquivo de token metrics invalido.' }
    $oldToken = [IO.File]::ReadAllText($tokenPath)
    if ($oldToken.Length -lt 32 -or $oldToken -match '\s') { throw 'Arquivo de token metrics invalido; revisar antes de rotacionar.' }
    try { $lookup = Invoke-MetricsAdmin 'Post' 'auth/token/lookup' @{ token = $oldToken } }
    catch {
      if ($Rotate -and $_.Exception.Response -and [int]$_.Exception.Response.StatusCode -in @(400, 403)) { $lookup = $null }
      else { throw 'Token metrics expirado/indisponivel; usar -Rotate apos verificar o Vault.' }
    }
    if ($lookup) { Assert-MetricsToken $lookup.data; $oldAccessor = $lookup.data.accessor }
    if (-not $Rotate) { Write-Output 'Token metrics valido preservado; TTL finito, sem renovacao automatica.'; return }
  }
  $created = Invoke-MetricsAdmin 'Post' 'auth/token/create-orphan' @{
    policies = @('securegate-metrics'); no_default_policy = $true; renewable = $false
    ttl = '1h'; explicit_max_ttl = '1h'; num_uses = 0; type = 'service'; display_name = 'securegate-metrics'
  }
  $token = $created.auth.client_token
  $newAccessor = $created.auth.accessor
  $verified = Invoke-MetricsAdmin 'Post' 'auth/token/lookup' @{ token = $token }
  Assert-MetricsToken $verified.data
  [IO.Directory]::CreateDirectory($directory) | Out-Null
  $temporary = Join-Path $directory ('vault-metrics-token.' + [Guid]::NewGuid().ToString('N') + '.tmp')
  [IO.File]::WriteAllText($temporary, $token, [Text.UTF8Encoding]::new($false))
  if (Test-Path -LiteralPath $tokenPath) { [IO.File]::Replace($temporary, $tokenPath, [NullString]::Value) }
  else { [IO.File]::Move($temporary, $tokenPath) }
  $installed = $true
  if ($oldAccessor) { Invoke-MetricsAdmin 'Post' 'auth/token/revoke-accessor' @{ accessor = $oldAccessor } | Out-Null }
  Write-Output 'Token Vault metrics instalado (1h, sem default/renovacao). Recriar Prometheus apos rotacao para atualizar bind mount.'
} catch {
  if (-not $installed -and $newAccessor) {
    try { Invoke-MetricsAdmin 'Post' 'auth/token/revoke-accessor' @{ accessor = $newAccessor } | Out-Null } catch { }
  }
  if ($installed) { throw 'Novo token instalado, mas a revogacao do anterior nao foi confirmada; revisar accessors com administracao.' }
  throw 'Provisionamento Vault metrics recusado; conferir politica, token, TTL e disponibilidade. Valores/respostas omitidos.'
} finally {
  if ($temporary -and (Test-Path -LiteralPath $temporary)) { [IO.File]::Delete($temporary) }
  $token = $null; $oldToken = $null; $adminHeaders = $null
}

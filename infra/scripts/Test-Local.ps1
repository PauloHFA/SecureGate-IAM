[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'LocalEnvironment.ps1')
$projectPath = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$envPath = Join-Path $projectPath '.env'
$composePath = Join-Path $projectPath 'infra/docker-compose.yml'
$localPath = Join-Path $projectPath 'infra/.local'
$settings = Read-LocalEnvironment $envPath

function Assert-Local {
  param([bool]$Condition, [string]$Message)
  if (-not $Condition) { throw $Message }
}

function Invoke-JsonApi {
  param([string]$Uri, [string]$Method = 'GET', [hashtable]$Headers = @{}, $Body)
  $request = @{ Uri = $Uri; Method = $Method; Headers = $Headers; TimeoutSec = 15 }
  if ($null -ne $Body) {
    $request.ContentType = 'application/json'
    $request.Body = [Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Depth 20 -Compress))
  }
  return Invoke-RestMethod @request
}

function Assert-Forbidden {
  param([string]$Uri, [string]$Method, [hashtable]$Headers, $Body)
  try {
    $null = Invoke-JsonApi -Uri $Uri -Method $Method -Headers $Headers -Body $Body
  } catch {
    if ($null -ne $_.Exception.Response -and [int]$_.Exception.Response.StatusCode -eq 403) { return }
    throw
  }
  throw 'Uma operacao privilegiada foi aceita pela identidade da aplicacao.'
}

# PostgreSQL: autenticação TCP, não apenas socket com trust local.
$sqlResult = 'SELECT 1;' | docker compose --env-file $envPath -f $composePath exec -T postgres sh -ec 'PGPASSWORD=$POSTGRES_PASSWORD exec psql -h127.0.0.1 -U$POSTGRES_USER -d$POSTGRES_DB -vON_ERROR_STOP=1 -At'
Assert-Local ($LASTEXITCODE -eq 0 -and ($sqlResult -join '').Trim() -eq '1') 'Consulta PostgreSQL falhou.'
Write-Output 'PASS PostgreSQL: consulta autenticada por TCP.'

$probeKey = 'smoke:' + [guid]::NewGuid().ToString('N')
$redisCommand = 'export REDISCLI_AUTH=$REDIS_PASSWORD; redis-cli SET ' + $probeKey + ' ready EX 30 NX; redis-cli GET ' + $probeKey + '; redis-cli DEL ' + $probeKey
$redisResult = docker compose --env-file $envPath -f $composePath exec -T redis sh -ec $redisCommand
Assert-Local ($LASTEXITCODE -eq 0 -and ($redisResult -join ',') -eq 'OK,ready,1') 'Escrita/leitura Redis falhou.'
Write-Output 'PASS Redis: escrita, leitura e remocao autenticadas.'

# RabbitMQ: verifica a configuração real; exercita mensagens em vhost temporário
# para nunca consumir ou apagar mensagens das filas de desenvolvimento.
$rabbitBase = 'http://127.0.0.1:' + $settings.RABBITMQ_MANAGEMENT_PORT + '/api'
$basic = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($settings.RABBITMQ_USER + ':' + $settings.RABBITMQ_PASSWORD))
$rabbitHeaders = @{ Authorization = 'Basic ' + $basic }
$vhost = 'securegate-smoke-' + [guid]::NewGuid().ToString('N')
$vhostCreated = $false
function Invoke-Rabbit {
  param([string]$Path, [string]$Method = 'GET', $Body)
  return Invoke-JsonApi -Uri ($rabbitBase + $Path) -Method $Method -Headers $rabbitHeaders -Body $Body
}
function Publish-Probe {
  param([string]$Exchange, [string]$Key, [string]$Payload)
  $result = Invoke-Rabbit "/exchanges/$vhost/$Exchange/publish" 'POST' @{
    properties = @{ delivery_mode = 2 }; routing_key = $Key
    payload = $Payload; payload_encoding = 'string'
  }
  Assert-Local ([bool]$result.routed) 'RabbitMQ nao roteou a mensagem de teste.'
}
function Get-Probe {
  param([string]$Queue, [string]$AckMode = 'ack_requeue_false')
  $messages = Invoke-Rabbit "/queues/$vhost/$Queue/get" 'POST' @{
    count = 1; ackmode = $AckMode; encoding = 'auto'; truncate = 10000
  }
  foreach ($message in $messages) { Write-Output $message }
}

try {
  $liveVhost = [Uri]::EscapeDataString($settings.RABBITMQ_VHOST)
  $topology = Get-Content (Join-Path $projectPath 'infra/rabbitmq/definitions.json') -Raw | ConvertFrom-Json
  foreach ($queue in $topology.queues) {
    $live = Invoke-Rabbit "/queues/$liveVhost/$($queue.name)"
    Assert-Local ([bool]$live.durable) "Fila nao duravel: $($queue.name)"
    foreach ($argument in $queue.arguments.PSObject.Properties) {
      $liveArgument = $live.arguments.PSObject.Properties[$argument.Name]
      Assert-Local ($null -ne $liveArgument -and $liveArgument.Value -eq $argument.Value) "Argumento incorreto em $($queue.name)"
    }
  }
  foreach ($exchange in $topology.exchanges) {
    $live = Invoke-Rabbit "/exchanges/$liveVhost/$($exchange.name)"
    Assert-Local ($live.durable -and $live.type -eq $exchange.type) "Exchange incorreta: $($exchange.name)"
  }
  foreach ($binding in $topology.bindings) {
    $liveBindings = @(Invoke-Rabbit "/bindings/$liveVhost/e/$($binding.source)/q/$($binding.destination)")
    Assert-Local (@($liveBindings | Where-Object { $_.routing_key -eq $binding.routing_key }).Count -gt 0) 'Binding de desenvolvimento ausente.'
  }
  $null = Invoke-Rabbit "/vhosts/$vhost" 'PUT' @{}
  $vhostCreated = $true
  $topology.vhosts[0].name = $vhost
  foreach ($collection in @('queues', 'exchanges', 'bindings')) {
    foreach ($item in $topology.$collection) { $item.vhost = $vhost }
  }
  $null = Invoke-Rabbit '/definitions' 'POST' $topology
  $payload = [guid]::NewGuid().ToString('N')
  Publish-Probe 'security.events' 'token.revoked' $payload
  $revoked = @(Get-Probe 'enforcer.revocation')
  $audit = @(Get-Probe 'audit.persist')
  Assert-Local ($revoked.Count -eq 1 -and $revoked[0].payload -eq $payload) 'Evento nao chegou ao enforcer.'
  Assert-Local ($audit.Count -eq 1 -and $audit[0].payload -eq $payload) 'Evento nao chegou a auditoria.'
  Assert-Local (@(Get-Probe 'enforcer.block-policy').Count -eq 0) 'Evento de revogacao roteado indevidamente para bloqueio.'

  Publish-Probe 'security.events' 'user.locked' $payload
  Assert-Local (@(Get-Probe 'enforcer.block-policy').Count -eq 1) 'Evento de bloqueio nao roteado.'
  $null = Get-Probe 'audit.persist'

  Publish-Probe 'security.events' 'token.revoked' $payload
  $null = Get-Probe 'enforcer.revocation' 'reject_requeue_false'
  $null = Get-Probe 'audit.persist'
  $deadline = [DateTime]::UtcNow.AddSeconds(10)
  do {
    $dead = @(Get-Probe 'enforcer.revocation.dlq')
    if ($dead.Count -eq 0) { Start-Sleep -Milliseconds 250 }
  } while ($dead.Count -eq 0 -and [DateTime]::UtcNow -lt $deadline)
  Assert-Local ($dead.Count -eq 1 -and $dead[0].payload -eq $payload) 'Rejeicao nao chegou a DLQ.'

  Publish-Probe 'security.events.retry' 'enforcer.revocation' $payload
  $deadline = [DateTime]::UtcNow.AddSeconds(15)
  do {
    $retried = @(Get-Probe 'enforcer.revocation')
    if ($retried.Count -eq 0) { Start-Sleep -Milliseconds 500 }
  } while ($retried.Count -eq 0 -and [DateTime]::UtcNow -lt $deadline)
  Assert-Local ($retried.Count -eq 1 -and $retried[0].payload -eq $payload) 'Retry nao retornou a fila original.'
  Assert-Local (@(Get-Probe 'audit.persist').Count -eq 0) 'Retry replicou indevidamente para auditoria.'
  Write-Output 'PASS RabbitMQ: topologia real, roteamento, isolamento, DLQ e retry com TTL.'
} finally {
  if ($vhostCreated) { $null = Invoke-Rabbit "/vhosts/$vhost" 'DELETE' }
}

$vaultBase = 'http://127.0.0.1:' + $settings.VAULT_PORT + '/v1'
$rootHeaders = @{ 'X-Vault-Token' = $settings.VAULT_DEV_ROOT_TOKEN_ID }
$key = Invoke-JsonApi -Uri "$vaultBase/transit/keys/securegate-jwt" -Headers $rootHeaders
Assert-Local ($key.data.type -eq 'rsa-3072' -and -not $key.data.exportable -and -not $key.data.allow_plaintext_backup) 'Chave Transit fora da configuracao esperada.'
$clientHeaders = $null
try {
  $login = Invoke-JsonApi -Uri "$vaultBase/auth/approle/login" -Method 'POST' -Body @{
    role_id = [IO.File]::ReadAllText((Join-Path $localPath 'iam-core-role-id')).Trim()
    secret_id = [IO.File]::ReadAllText((Join-Path $localPath 'iam-core-secret-id')).Trim()
  }
  $clientHeaders = @{ 'X-Vault-Token' = $login.auth.client_token }
  $secret = Invoke-JsonApi -Uri "$vaultBase/secret/data/securegate/iam-core" -Headers $clientHeaders
  Assert-Local ($secret.data.data.postgres_password -eq $settings.POSTGRES_PASSWORD) 'Segredo KV divergente do ambiente local.'
  $inputData = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('securegate-stage1-probe'))
  $signed = Invoke-JsonApi -Uri "$vaultBase/transit/sign/securegate-jwt" -Method 'POST' -Headers $clientHeaders -Body @{
    input = $inputData; hash_algorithm = 'sha2-256'; signature_algorithm = 'pkcs1v15'; prehashed = $false
  }
  $verified = Invoke-JsonApi -Uri "$vaultBase/transit/verify/securegate-jwt" -Method 'POST' -Headers $rootHeaders -Body @{
    input = $inputData; signature = $signed.data.signature
    hash_algorithm = 'sha2-256'; signature_algorithm = 'pkcs1v15'; prehashed = $false
  }
  Assert-Local ([bool]$verified.data.valid) 'Assinatura Transit nao verificada.'
  Assert-Forbidden "$vaultBase/transit/keys/securegate-jwt/rotate" 'POST' $clientHeaders @{}
  Assert-Forbidden "$vaultBase/transit/export/signing-key/securegate-jwt" 'GET' $clientHeaders $null
  Assert-Forbidden "$vaultBase/secret/data/securegate/policy-enforcer" 'GET' $clientHeaders $null
  Write-Output 'PASS Vault iam-core: AppRole, KV e assinatura RSA; rotacao, exportacao e segredo alheio negados.'
} finally {
  if ($null -ne $clientHeaders) {
    $null = Invoke-JsonApi -Uri "$vaultBase/auth/token/revoke-self" -Method 'POST' -Headers $clientHeaders -Body @{}
  }
}

$clientHeaders = $null
try {
  $login = Invoke-JsonApi -Uri "$vaultBase/auth/approle/login" -Method 'POST' -Body @{
    role_id = [IO.File]::ReadAllText((Join-Path $localPath 'policy-enforcer-role-id')).Trim()
    secret_id = [IO.File]::ReadAllText((Join-Path $localPath 'policy-enforcer-secret-id')).Trim()
  }
  $clientHeaders = @{ 'X-Vault-Token' = $login.auth.client_token }
  $secret = Invoke-JsonApi -Uri "$vaultBase/secret/data/securegate/policy-enforcer" -Headers $clientHeaders
  Assert-Local ($secret.data.data.redis_password -eq $settings.REDIS_PASSWORD) 'Segredo do enforcer divergente.'
  Assert-Forbidden "$vaultBase/transit/sign/securegate-jwt" 'POST' $clientHeaders @{ input = 'dGVzdA==' }
  Write-Output 'PASS Vault policy-enforcer: AppRole e KV; permissao de assinatura negada.'
} finally {
  if ($null -ne $clientHeaders) {
    $null = Invoke-JsonApi -Uri "$vaultBase/auth/token/revoke-self" -Method 'POST' -Headers $clientHeaders -Body @{}
  }
}
Write-Output 'Smoke test da etapa 1 concluido sem exibir credenciais.'

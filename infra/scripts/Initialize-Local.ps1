[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'LocalEnvironment.ps1')
$projectPath = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$envPath = Join-Path $projectPath '.env'
$localPath = Join-Path $projectPath 'infra/.local'
New-Item -ItemType Directory -Path $localPath -Force | Out-Null

if (-not (Test-Path -LiteralPath $envPath)) {
  $template = [IO.File]::ReadAllText((Join-Path $projectPath '.env.example'))
  foreach ($variable in @('POSTGRES_PASSWORD', 'REDIS_PASSWORD', 'RABBITMQ_PASSWORD', 'VAULT_DEV_ROOT_TOKEN_ID', 'IAM_BOOTSTRAP_TOKEN')) {
    $template = $template.Replace("$variable=GENERATED_BY_INITIALIZE_LOCAL", "$variable=$(New-LocalSecret)")
  }
  Write-Utf8File $envPath $template
  Write-Output '.env criado com credenciais aleatorias; valores nao exibidos.'
} else {
  Write-Output '.env existente preservado.'
}

$settings = Read-LocalEnvironment $envPath
if (-not $settings.ContainsKey('IAM_BOOTSTRAP_TOKEN')) {
  $content = [IO.File]::ReadAllText($envPath).TrimEnd() + "`nIAM_BOOTSTRAP_ENABLED=false`nIAM_BOOTSTRAP_TOKEN=$(New-LocalSecret)`n"
  Write-Utf8File $envPath $content
  Write-Output 'Token bootstrap local adicionado; administracao continua desabilitada por padrao.'
  $settings = Read-LocalEnvironment $envPath
}
$topologyPath = Join-Path $projectPath 'infra/rabbitmq/definitions.json'
$definitions = Get-Content -LiteralPath $topologyPath -Raw | ConvertFrom-Json
$salt = New-Object byte[] 4
$rng = [Security.Cryptography.RandomNumberGenerator]::Create()
try { $rng.GetBytes($salt) } finally { $rng.Dispose() }
$sha = [Security.Cryptography.SHA256]::Create()
try {
  $digest = $sha.ComputeHash([byte[]]($salt + [Text.Encoding]::UTF8.GetBytes($settings.RABBITMQ_PASSWORD)))
} finally { $sha.Dispose() }
$passwordHash = [Convert]::ToBase64String([byte[]]($salt + $digest))
$definitions | Add-Member -NotePropertyName users -NotePropertyValue @(@{
  name = $settings.RABBITMQ_USER
  password_hash = $passwordHash
  hashing_algorithm = 'rabbit_password_hashing_sha256'
  tags = @('administrator')
})
$definitions | Add-Member -NotePropertyName permissions -NotePropertyValue @(@{
  user = $settings.RABBITMQ_USER; vhost = $settings.RABBITMQ_VHOST
  configure = '.*'; write = '.*'; read = '.*'
})
$definitions.vhosts[0].name = $settings.RABBITMQ_VHOST
foreach ($collection in @('queues', 'exchanges', 'bindings')) {
  foreach ($item in $definitions.$collection) { $item.vhost = $settings.RABBITMQ_VHOST }
}
Write-Utf8File (Join-Path $localPath 'rabbitmq-definitions.json') ($definitions | ConvertTo-Json -Depth 15)
Write-Output 'Definicoes RabbitMQ geradas em infra/.local/ sem senha em texto puro.'
Write-Output 'Proximo comando, na raiz: docker compose --env-file .env -f infra/docker-compose.yml up -d --wait'

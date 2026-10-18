[CmdletBinding()]
param([ValidateRange(1024, 65535)][int]$Port = 8080, [switch]$EnableBootstrap, [switch]$EnableTransit, [switch]$EnableAuthorization)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'LocalEnvironment.ps1')
$projectPath = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$settings = Read-LocalEnvironment (Join-Path $projectPath '.env')
if ($EnableAuthorization) { $EnableTransit = $true }
if ($EnableBootstrap -and (-not $settings.ContainsKey('IAM_BOOTSTRAP_TOKEN') -or $settings.IAM_BOOTSTRAP_TOKEN.Length -lt 32)) {
  throw 'Token bootstrap ausente ou invalido. Execute Initialize-Local.ps1 antes de ativar a administracao.'
}
$jarPath = Join-Path $projectPath 'iam-core/target/iam-core-0.1.0-SNAPSHOT.jar'
$roleIdPath = Join-Path $projectPath 'infra/.local/iam-core-role-id'
$secretIdPath = Join-Path $projectPath 'infra/.local/iam-core-secret-id'
if ($EnableTransit -and (-not (Test-Path -LiteralPath $roleIdPath) -or -not (Test-Path -LiteralPath $secretIdPath))) {
  throw 'Arquivos AppRole ausentes. Execute o bootstrap Vault documentado em infra/README.md.'
}
if (-not (Test-Path -LiteralPath $jarPath)) {
  throw 'JAR ausente. Execute .\mvnw.cmd verify na raiz antes de iniciar.'
}
$applicationEnvironment = @{
  IAM_DATABASE_URL = 'jdbc:postgresql://127.0.0.1:' + $settings.POSTGRES_PORT + '/' + $settings.POSTGRES_DB
  IAM_DATABASE_USERNAME = $settings.POSTGRES_USER
  IAM_DATABASE_PASSWORD = $settings.POSTGRES_PASSWORD
  IAM_PORT = [string]$Port
  IAM_BIND_ADDRESS = '127.0.0.1'
  IAM_TRANSIT_ENABLED = $(if ($EnableTransit) { 'true' } else { 'false' })
  IAM_AUTHORIZATION_ENABLED = $(if ($EnableAuthorization) { 'true' } else { 'false' })
  IAM_ISSUER = 'http://127.0.0.1:' + $Port
  IAM_VAULT_ADDRESS = 'http://127.0.0.1:' + $settings.VAULT_PORT
  IAM_VAULT_ROLE_ID_FILE = $roleIdPath
  IAM_VAULT_SECRET_ID_FILE = $secretIdPath
  IAM_BOOTSTRAP_ENABLED = $(if ($EnableBootstrap) { 'true' } else { 'false' })
  IAM_BOOTSTRAP_TOKEN = $(if ($EnableBootstrap) { $settings.IAM_BOOTSTRAP_TOKEN } else { '' })
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

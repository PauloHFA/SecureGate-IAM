[CmdletBinding()]
param([ValidateRange(1024, 65535)][int]$Port = 8080)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'LocalEnvironment.ps1')
$projectPath = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$settings = Read-LocalEnvironment (Join-Path $projectPath '.env')
$jarPath = Join-Path $projectPath 'iam-core/target/iam-core-0.1.0-SNAPSHOT.jar'
if (-not (Test-Path -LiteralPath $jarPath)) {
  throw 'JAR ausente. Execute .\mvnw.cmd verify na raiz antes de iniciar.'
}
$applicationEnvironment = @{
  IAM_DATABASE_URL = 'jdbc:postgresql://127.0.0.1:' + $settings.POSTGRES_PORT + '/' + $settings.POSTGRES_DB
  IAM_DATABASE_USERNAME = $settings.POSTGRES_USER
  IAM_DATABASE_PASSWORD = $settings.POSTGRES_PASSWORD
  IAM_PORT = [string]$Port
  IAM_BIND_ADDRESS = '127.0.0.1'
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

[CmdletBinding()]
param([switch]$EnableConsumer)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'LocalEnvironment.ps1')
$projectPath = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$settings = Read-LocalEnvironment (Join-Path $projectPath '.env')
$jarPath = Join-Path $projectPath 'policy-enforcer/target/policy-enforcer-0.1.0-SNAPSHOT.jar'
if (-not (Test-Path -LiteralPath $jarPath)) { throw 'JAR ausente. Execute .\mvnw.cmd verify na raiz.' }
$applicationEnvironment = @{
  ENFORCER_CONSUMER_ENABLED = $(if ($EnableConsumer) { 'true' } else { 'false' })
  ENFORCER_RABBITMQ_HOST = '127.0.0.1'
  ENFORCER_RABBITMQ_PORT = $settings.RABBITMQ_PORT
  ENFORCER_RABBITMQ_VHOST = $settings.RABBITMQ_VHOST
  ENFORCER_RABBITMQ_USERNAME = $(if ($EnableConsumer) { $settings.RABBITMQ_USER } else { '' })
  ENFORCER_RABBITMQ_PASSWORD = $(if ($EnableConsumer) { $settings.RABBITMQ_PASSWORD } else { '' })
  ENFORCER_REDIS_HOST = '127.0.0.1'
  ENFORCER_REDIS_PORT = $settings.REDIS_PORT
  ENFORCER_REDIS_PASSWORD = $(if ($EnableConsumer) { $settings.REDIS_PASSWORD } else { '' })
}
$previousValues = @{}
try {
  foreach ($key in $applicationEnvironment.Keys) {
    $previousValues[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
    [Environment]::SetEnvironmentVariable($key, $applicationEnvironment[$key], 'Process')
  }
  Write-Output 'Iniciando policy-enforcer sem servidor HTTP; use Ctrl+C para encerrar.'
  & java -jar $jarPath
  if ($LASTEXITCODE -ne 0) { throw 'policy-enforcer encerrou com falha; consulte o log local.' }
} finally {
  foreach ($key in $previousValues.Keys) {
    [Environment]::SetEnvironmentVariable($key, $previousValues[$key], 'Process')
  }
}

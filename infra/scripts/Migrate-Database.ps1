[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'LocalEnvironment.ps1')
$projectPath = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$settings = Read-LocalEnvironment (Join-Path $projectPath '.env')
$values = @{
  IAM_MIGRATION_DATABASE_URL = 'jdbc:postgresql://127.0.0.1:' + $settings.POSTGRES_PORT + '/' + $settings.POSTGRES_DB
  IAM_MIGRATION_DATABASE_USERNAME = $settings.POSTGRES_USER
  IAM_MIGRATION_DATABASE_PASSWORD = $settings.POSTGRES_PASSWORD
}
$previous = @{}
try {
  foreach ($key in $values.Keys) {
    $previous[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
    [Environment]::SetEnvironmentVariable($key, $values[$key], 'Process')
  }
  & java -jar (Join-Path $projectPath 'iam-core/target/iam-core-0.1.0-SNAPSHOT.jar') --migrate-only
  if ($LASTEXITCODE -ne 0) { throw 'Migracao recusada; investigue o banco antes de iniciar o runtime.' }
} finally {
  foreach ($key in $previous.Keys) { [Environment]::SetEnvironmentVariable($key, $previous[$key], 'Process') }
}

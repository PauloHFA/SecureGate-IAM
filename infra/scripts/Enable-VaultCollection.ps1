[CmdletBinding()]
param([ValidateRange(1024, 65535)][int]$VaultPort = 8200)
$ErrorActionPreference = 'Stop'
$projectPath = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$directory = Join-Path $projectPath 'infra/.local/observability'
$configPath = Join-Path $directory 'prometheus.yml'
$envPath = Join-Path $directory 'compose.env'
foreach ($path in @($configPath, $envPath, (Join-Path $projectPath 'infra/.local/vault-metrics-token'))) {
  if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw 'Execute Initialize-Observability.ps1 antes de incluir Vault.' }
}
$config = [IO.File]::ReadAllText($configPath)
$environment = [IO.File]::ReadAllText($envPath)
if ($config -match 'job_name:\s*["'']?vault(?:["'']?\s|$)') { throw 'Coleta Vault ja configurada.' }
$ports = @([regex]::Matches($config, 'host[.]docker[.]internal:(\d+)') | ForEach-Object { [int]$_.Groups[1].Value })
$ports += @([regex]::Matches($environment, '(?m)^(?:PROMETHEUS_PORT|GRAFANA_PORT)=(\d+)') | ForEach-Object { [int]$_.Groups[1].Value })
if ($VaultPort -in $ports) { throw 'Porta Vault deve ser distinta das demais portas de coleta.' }
foreach ($path in @($configPath, $envPath)) { if (Test-Path -LiteralPath ($path + '.before-vault')) { throw 'Backup existente; revisar antes de repetir migracao.' } }
$template = [IO.File]::ReadAllText((Join-Path $projectPath 'infra/observability/vault-scrape.template.yml'))
# Run with collectors stopped. Only scrape configuration is changed, never credentials/volumes.
foreach ($path in @($configPath, $envPath)) { [IO.File]::Copy($path, ($path + '.before-vault'), $false) }
[IO.File]::WriteAllText($configPath, ($config.TrimEnd() + "`n" + $template.Replace('__VAULT_PORT__', [string]$VaultPort)), [Text.UTF8Encoding]::new($false))
[IO.File]::WriteAllText($envPath, ([regex]::Replace($environment, '(?m)^OBS_VAULT=[^\r\n]*[\r\n]*', '').TrimEnd() + "`nOBS_VAULT=true`n"), [Text.UTF8Encoding]::new($false))
Write-Output 'Job Vault adicionado com backup privado. Token proprio montado pelo overlay da coleta; depois execute Start-Observability.ps1.'

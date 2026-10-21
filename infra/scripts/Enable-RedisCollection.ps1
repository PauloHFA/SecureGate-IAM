[CmdletBinding()]
param([ValidateRange(1024, 65535)][int]$RedisMetricsPort = 19121)
$ErrorActionPreference = 'Stop'
$projectPath = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$directory = Join-Path $projectPath 'infra/.local/observability'
$configPath = Join-Path $directory 'prometheus.yml'
$envPath = Join-Path $directory 'compose.env'
foreach ($path in @($configPath, $envPath)) {
  if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw 'Execute Initialize-Observability.ps1 antes de incluir Redis.' }
}
$config = [IO.File]::ReadAllText($configPath)
$environment = [IO.File]::ReadAllText($envPath)
if ($config -match 'job_name:\s*["'']?redis(?:["'']?\s|$)') { throw 'Coleta Redis ja configurada.' }
$ports = @([regex]::Matches($config, 'host[.]docker[.]internal:(\d+)') | ForEach-Object { [int]$_.Groups[1].Value })
$ports += @([regex]::Matches($environment, '(?m)^(?:PROMETHEUS_PORT|GRAFANA_PORT)=(\d+)') | ForEach-Object { [int]$_.Groups[1].Value })
if ($RedisMetricsPort -in $ports) { throw 'Porta Redis deve ser distinta das demais portas de coleta.' }
if (Test-Path -LiteralPath ($configPath + '.before-redis')) { throw 'Backup existente; revisar antes de repetir migracao.' }
$template = [IO.File]::ReadAllText((Join-Path $projectPath 'infra/observability/redis-scrape.template.yml'))
# Run with collectors stopped. Only scrape configuration is changed, never credentials/volumes.
[IO.File]::Copy($configPath, ($configPath + '.before-redis'), $false)
[IO.File]::WriteAllText($configPath, ($config.TrimEnd() + "`n" + $template.Replace('__REDIS_METRICS_PORT__', [string]$RedisMetricsPort)), [Text.UTF8Encoding]::new($false))
Write-Output 'Job Redis adicionado com backup privado. Prepare a ACL e o exporter opcional; depois execute Start-Observability.ps1.'

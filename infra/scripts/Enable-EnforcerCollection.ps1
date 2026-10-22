[CmdletBinding()]
param([ValidateRange(1024, 65535)][int]$EnforcerPort = 19091)
$ErrorActionPreference = 'Stop'
$projectPath = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$directory = Join-Path $projectPath 'infra/.local/observability'
$configPath = Join-Path $directory 'prometheus.yml'
$envPath = Join-Path $directory 'compose.env'
$tokenPath = Join-Path $projectPath 'infra/.local/enforcer-metrics-token'
foreach ($path in @($configPath, $envPath, $tokenPath)) {
  if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw 'Preparacao existente e token Enforcer sao obrigatorios.' }
}
$token = [IO.File]::ReadAllText($tokenPath).Trim()
if ($token.Length -lt 32 -or $token.Length -gt 256 -or $token -match '\s') { throw 'Token Enforcer invalido.' }
$token = $null
$config = [IO.File]::ReadAllText($configPath)
$environment = [IO.File]::ReadAllText($envPath)
if ($config -match 'job_name:\s*policy-enforcer' -or $environment -match '(?m)^OBS_ENFORCER=true\s*$') { throw 'Coleta do enforcer ja configurada.' }
$ports = @([regex]::Matches($config, 'host[.]docker[.]internal:(\d+)') | ForEach-Object { [int]$_.Groups[1].Value })
$ports += @([regex]::Matches($environment, '(?m)^(?:PROMETHEUS_PORT|GRAFANA_PORT)=(\d+)') | ForEach-Object { [int]$_.Groups[1].Value })
if ($EnforcerPort -in $ports) { throw 'Porta do enforcer deve ser distinta das demais.' }
foreach ($path in @($configPath, $envPath)) {
  if (Test-Path -LiteralPath ($path + '.before-enforcer')) { throw 'Backup existente; revisar antes de repetir migracao.' }
}
# Explicit local migration; run with collectors stopped. Credentials and volumes are preserved.
foreach ($path in @($configPath, $envPath)) { [IO.File]::Copy($path, ($path + '.before-enforcer'), $false) }
$template = [IO.File]::ReadAllText((Join-Path $projectPath 'infra/observability/enforcer-scrape.template.yml'))
$config = $config.TrimEnd() + "`n" + $template.Replace('__ENFORCER_PORT__', [string]$EnforcerPort)
$environment = [regex]::Replace($environment, '(?m)^OBS_ENFORCER=[^\r\n]*[\r\n]*', '')
$environment = $environment.TrimEnd() + "`nOBS_ENFORCER=true`n"
[IO.File]::WriteAllText($configPath, $config, [Text.UTF8Encoding]::new($false))
[IO.File]::WriteAllText($envPath, $environment, [Text.UTF8Encoding]::new($false))
Write-Output 'Job Enforcer adicionado com backups privados. Senhas e volumes preservados; iniciar a coleta com Start-Observability.ps1.'

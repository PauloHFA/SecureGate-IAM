[CmdletBinding()]
param(
  [ValidateRange(1024, 65535)][int]$IamPort = 8080,
  [ValidateRange(1024, 65535)][int]$PrometheusPort = 19090,
  [ValidateRange(1024, 65535)][int]$GrafanaPort = 13000,
  [switch]$IncludeEnforcer,
  [ValidateRange(1024, 65535)][int]$EnforcerPort = 19091
)
$ErrorActionPreference = 'Stop'
if (@($IamPort, $PrometheusPort, $GrafanaPort | Select-Object -Unique).Count -ne 3) { throw 'Use tres portas distintas.' }
if ($IncludeEnforcer -and $EnforcerPort -in @($IamPort, $PrometheusPort, $GrafanaPort)) { throw 'Porta do enforcer deve ser distinta das demais.' }
$projectPath = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$metricsPath = Join-Path $projectPath 'infra/.local/iam-metrics-token'
if (-not (Test-Path -LiteralPath $metricsPath -PathType Leaf)) { throw 'Execute Initialize-MetricsToken.ps1 antes de preparar a coleta.' }
$metricsToken = [IO.File]::ReadAllText($metricsPath).Trim()
if ($metricsToken.Length -lt 32 -or $metricsToken.Length -gt 256 -or $metricsToken -match '\s') { throw 'Arquivo de token de metricas invalido.' }
$metricsToken = $null
if ($IncludeEnforcer) {
  $enforcerTokenPath = Join-Path $projectPath 'infra/.local/enforcer-metrics-token'
  if (-not (Test-Path -LiteralPath $enforcerTokenPath -PathType Leaf)) { throw 'Execute Initialize-MetricsToken.ps1 -Target Enforcer antes de incluir o enforcer.' }
  $readToken = [IO.File]::ReadAllText($enforcerTokenPath).Trim()
  if ($readToken.Length -lt 32 -or $readToken.Length -gt 256 -or $readToken -match '\s') { throw 'Arquivo de token de metricas do enforcer invalido.' }
  $readToken = $null
}
$destination = Join-Path $projectPath 'infra/.local/observability'
[IO.Directory]::CreateDirectory($destination) | Out-Null
foreach ($name in @('prometheus.yml', 'compose.env', 'grafana-admin-password')) {
  if (Test-Path -LiteralPath (Join-Path $destination $name)) { throw 'Configuracao existente; nao sobrescrever credenciais/portas de uma stack em uso.' }
}
function Write-NewPrivateFile([string]$Name, [string]$Content) {
  $stream = [IO.File]::Open((Join-Path $destination $Name), [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
  try { $bytes = [Text.UTF8Encoding]::new($false).GetBytes($Content); $stream.Write($bytes, 0, $bytes.Length) }
  finally { $stream.Dispose() }
}
$random = [Security.Cryptography.RandomNumberGenerator]::Create()
$entropy = New-Object byte[] 32
try { $random.GetBytes($entropy) } finally { $random.Dispose() }
$password = ([BitConverter]::ToString($entropy)).Replace('-', '').ToLowerInvariant()
try {
  Write-NewPrivateFile 'grafana-admin-password' $password
  $template = [IO.File]::ReadAllText((Join-Path $projectPath 'infra/observability/prometheus.template.yml'))
  $prometheusConfig = $template.Replace('__IAM_PORT__', [string]$IamPort)
  if ($IncludeEnforcer) {
    $enforcerTemplate = [IO.File]::ReadAllText((Join-Path $projectPath 'infra/observability/enforcer-scrape.template.yml'))
    $prometheusConfig = $prometheusConfig.TrimEnd() + "`n" + $enforcerTemplate.Replace('__ENFORCER_PORT__', [string]$EnforcerPort)
  }
  Write-NewPrivateFile 'prometheus.yml' $prometheusConfig
  Write-NewPrivateFile 'compose.env' "PROMETHEUS_PORT=$PrometheusPort`nGRAFANA_PORT=$GrafanaPort`nOBS_ENFORCER=$($IncludeEnforcer.IsPresent.ToString().ToLowerInvariant())`n"
} finally { $password = $null; $entropy = $null }
Write-Output 'Configuracao privada de observabilidade criada. Grafana usa admin e senha no arquivo grafana-admin-password; nenhum segredo foi impresso.'

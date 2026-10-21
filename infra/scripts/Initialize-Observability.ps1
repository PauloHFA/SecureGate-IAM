[CmdletBinding()]
param(
  [ValidateRange(1024, 65535)][int]$IamPort = 8080,
  [ValidateRange(1024, 65535)][int]$PrometheusPort = 19090,
  [ValidateRange(1024, 65535)][int]$GrafanaPort = 13000
)
$ErrorActionPreference = 'Stop'
if (@($IamPort, $PrometheusPort, $GrafanaPort | Select-Object -Unique).Count -ne 3) { throw 'Use tres portas distintas.' }
$projectPath = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$metricsPath = Join-Path $projectPath 'infra/.local/iam-metrics-token'
if (-not (Test-Path -LiteralPath $metricsPath -PathType Leaf)) { throw 'Execute Initialize-MetricsToken.ps1 antes de preparar a coleta.' }
$metricsToken = [IO.File]::ReadAllText($metricsPath).Trim()
if ($metricsToken.Length -lt 32 -or $metricsToken.Length -gt 256 -or $metricsToken -match '\s') { throw 'Arquivo de token de metricas invalido.' }
$metricsToken = $null
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
  Write-NewPrivateFile 'prometheus.yml' $template.Replace('__IAM_PORT__', [string]$IamPort)
  Write-NewPrivateFile 'compose.env' "PROMETHEUS_PORT=$PrometheusPort`nGRAFANA_PORT=$GrafanaPort`n"
} finally { $password = $null; $entropy = $null }
Write-Output 'Configuracao privada de observabilidade criada. Grafana usa admin e senha no arquivo grafana-admin-password; nenhum segredo foi impresso.'

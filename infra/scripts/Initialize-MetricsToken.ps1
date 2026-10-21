[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$projectPath = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$privateDirectory = Join-Path $projectPath 'infra/.local'
[IO.Directory]::CreateDirectory($privateDirectory) | Out-Null
$destination = Join-Path $privateDirectory 'iam-metrics-token'
$entropy = New-Object byte[] 32
$random = [Security.Cryptography.RandomNumberGenerator]::Create()
try { $random.GetBytes($entropy) } finally { $random.Dispose() }
$token = ([BitConverter]::ToString($entropy)).Replace('-', '').ToLowerInvariant()
# CreateNew refuses overwrite, including when another initializer wins the race.
$stream = [IO.File]::Open($destination, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
try {
  $bytes = [Text.Encoding]::UTF8.GetBytes($token)
  $stream.Write($bytes, 0, $bytes.Length)
} finally {
  $stream.Dispose()
  $token = $null
  $bytes = $null
  $entropy = $null
}
Write-Output 'Token de leitura de metricas criado em infra/.local/iam-metrics-token. Proteja o arquivo; reinicie o IAM apos rotacao.'

Set-StrictMode -Version Latest

function Read-LocalEnvironment {
  param([string]$Path)
  if (-not (Test-Path -LiteralPath $Path)) {
    throw 'Arquivo .env ausente. Execute infra/scripts/Initialize-Local.ps1.'
  }
  $values = @{}
  foreach ($line in Get-Content -LiteralPath $Path) {
    if ($line -match '^([A-Z_][A-Z0-9_]*)=(.*)$') {
      $values[$matches[1]] = $matches[2]
    }
  }
  foreach ($name in @('POSTGRES_DB', 'POSTGRES_USER', 'POSTGRES_PASSWORD',
      'REDIS_PASSWORD', 'RABBITMQ_USER', 'RABBITMQ_PASSWORD', 'RABBITMQ_VHOST',
      'VAULT_DEV_ROOT_TOKEN_ID', 'POSTGRES_PORT', 'REDIS_PORT', 'RABBITMQ_PORT',
      'RABBITMQ_MANAGEMENT_PORT', 'VAULT_PORT')) {
    if (-not $values.ContainsKey($name) -or [string]::IsNullOrWhiteSpace($values[$name]) -or
        $values[$name] -eq 'GENERATED_BY_INITIALIZE_LOCAL') {
      throw "Variavel local ausente ou placeholder: $name"
    }
  }
  return $values
}

function Write-Utf8File {
  param([string]$Path, [string]$Content)
  [IO.File]::WriteAllText($Path, $Content.Replace("`r`n", "`n"), (New-Object Text.UTF8Encoding $false))
}

function New-LocalSecret {
  $bytes = New-Object byte[] 32
  $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
  try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
  return -join ($bytes | ForEach-Object { $_.ToString('x2') })
}

[CmdletBinding()]
param(
  [Parameter(Mandatory)][uri]$BaseUri,
  [Parameter(Mandatory)][string]$AdminTokenFile,
  [Parameter(Mandatory)][string]$OutputFile,
  [ValidateRange(1, 100)][int]$Count = 2,
  [ValidatePattern('^[a-z][a-z0-9_-]{2,40}$')][string]$Prefix = 'load-login'
)
$ErrorActionPreference = 'Stop'
if ($BaseUri.Host -notin @('127.0.0.1', 'localhost') -or $BaseUri.Scheme -notin @('http', 'https') -or
    $BaseUri.UserInfo -or $BaseUri.Query -or $BaseUri.Fragment -or $BaseUri.AbsolutePath -ne '/') {
  throw 'Use a loopback base URI without credentials, query or path.'
}
$destination = [IO.Path]::GetFullPath($OutputFile)
$projectRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$privateRoots = @((Join-Path $projectRoot 'load-tests/.local'), (Join-Path $projectRoot 'integration-tests/target'))
$comparison = $(if ($env:OS -eq 'Windows_NT') { [StringComparison]::OrdinalIgnoreCase } else { [StringComparison]::Ordinal })
$allowed = $false
foreach ($root in $privateRoots) {
  $prefixPath = [IO.Path]::GetFullPath($root).TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
  if ($destination.StartsWith($prefixPath, $comparison)) { $allowed = $true }
}
if (-not $allowed) { throw 'Write credentials only under load-tests/.local or integration-tests/target.' }
if (Test-Path -LiteralPath $destination) { throw 'Mass file already exists; use a fresh namespace and file.' }
$token = [IO.File]::ReadAllText((Resolve-Path -LiteralPath $AdminTokenFile)).Trim()
if ([string]::IsNullOrWhiteSpace($token) -or $token -match '[\r\n]') { throw 'Invalid administrative token file.' }
$rows = @()
try {
  for ($index = 1; $index -le $Count; $index++) {
    $clientId = '{0}-{1:d4}' -f $Prefix, $index
    $email = "$clientId@example.test"
    $entropy = New-Object byte[] 24
    $random = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $random.GetBytes($entropy) } finally { $random.Dispose() }
    $password = 'Load!' + [Convert]::ToBase64String($entropy).Replace('+', '-').Replace('/', '_')
    $unknownEmail = 'absent-' + [Guid]::NewGuid().ToString('N') + '@example.test'
    try {
      $userBody = @{ email = $email; displayName = "Synthetic $clientId"; password = $password } | ConvertTo-Json -Compress
      $user = Invoke-RestMethod -Method Post -Uri ([uri]::new($BaseUri, '/api/admin/users')) `
        -Headers @{ Authorization = "Bearer $token" } -ContentType 'application/json' -Body $userBody -TimeoutSec 10
    } catch {
      throw 'Login mass creation failed. Earlier users may exist; use a fresh test environment or namespace.'
    }
    $rows += [pscustomobject]@{ email = $email; password = $password; user_id = $user.id; unknown_email = $unknownEmail }
  }
  [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($destination)) | Out-Null
  $csv = ($rows | ConvertTo-Csv -NoTypeInformation) -join "`n"
  [IO.File]::WriteAllText($destination, $csv + "`n", [Text.UTF8Encoding]::new($false))
  Write-Output "Created $Count synthetic users and absent-user candidates. Protect the generated mass file as a credential file."
} finally {
  $token = $null
  $created = $null
  $rows = $null
  $csv = $null
  $password = $null
  $userBody = $null
}

[CmdletBinding()]
param(
  [Parameter(Mandatory)][uri]$BaseUri,
  [Parameter(Mandatory)][string]$AdminTokenFile,
  [Parameter(Mandatory)][string]$OutputFile,
  [ValidateRange(1, 100)][int]$Count = 2,
  [ValidatePattern('^[a-z][a-z0-9_-]{2,40}$')][string]$Prefix = 'load-smoke'
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
    $body = @{ clientId = $clientId; name = "Synthetic $clientId"; audience = 'load-test'; scopes = @('load:read') } | ConvertTo-Json -Compress
    try {
      $created = Invoke-RestMethod -Method Post -Uri ([uri]::new($BaseUri, '/api/admin/oauth-clients')) `
        -Headers @{ Authorization = "Bearer $token" } -ContentType 'application/json' -Body $body -TimeoutSec 10
    } catch {
      throw 'Synthetic client creation failed. Earlier clients may exist; use a fresh isolated environment or namespace.'
    }
    if ([string]::IsNullOrWhiteSpace($created.clientSecret)) { throw 'Client creation returned no credential.' }
    $rows += [pscustomobject]@{ client_id = $clientId; client_secret = $created.clientSecret; scope = 'load:read' }
  }
  [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($destination)) | Out-Null
  $csv = ($rows | ConvertTo-Csv -NoTypeInformation) -join "`n"
  [IO.File]::WriteAllText($destination, $csv + "`n", [Text.UTF8Encoding]::new($false))
  Write-Output "Created $Count synthetic clients. Protect the generated mass file as a credential file."
} finally {
  $token = $null
  $created = $null
  $rows = $null
  $csv = $null
}

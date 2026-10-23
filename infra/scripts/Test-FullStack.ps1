[CmdletBinding()]
param(
  [uri]$BaseUri = [uri]'http://127.0.0.1:8080',
  [ValidateRange(5, 300)][int]$TimeoutSeconds = 60
)
$ErrorActionPreference = 'Stop'

if ($BaseUri.Host -notin @('127.0.0.1', 'localhost') -or $BaseUri.Scheme -notin @('http', 'https')) {
  throw 'BaseUri must be a local loopback endpoint.'
}

$base = $BaseUri.ToString().TrimEnd('/')
Write-Output "=== Starting Full Stack Smoke Test against $base ==="

# 1. Wait for readiness probe
$deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
$ready = $false
Write-Output "Checking /actuator/health/readiness (timeout: ${TimeoutSeconds}s)..."
while ([DateTime]::UtcNow -lt $deadline) {
  try {
    $resp = Invoke-RestMethod -Uri "$base/actuator/health/readiness" -Method Get -TimeoutSec 3 -ErrorAction Stop
    if ($resp.status -eq 'UP') {
      $ready = $true
      break
    }
  } catch {
    Start-Sleep -Seconds 2
  }
}
if (-not $ready) {
  throw "Readiness probe failed or timed out: $base/actuator/health/readiness"
}
Write-Output "  [PASS] Readiness probe is UP"

# 2. Check liveness probe
Write-Output "Checking /actuator/health/liveness..."
$liveResp = Invoke-RestMethod -Uri "$base/actuator/health/liveness" -Method Get -TimeoutSec 3
if ($liveResp.status -ne 'UP') {
  throw "Liveness probe is not UP: $($liveResp | ConvertTo-Json -Compress)"
}
Write-Output "  [PASS] Liveness probe is UP"

# 3. Check OIDC discovery
Write-Output "Checking /.well-known/openid-configuration..."
$discovery = Invoke-RestMethod -Uri "$base/.well-known/openid-configuration" -Method Get -TimeoutSec 3
if ([string]::IsNullOrWhiteSpace($discovery.issuer) -or [string]::IsNullOrWhiteSpace($discovery.jwks_uri) -or [string]::IsNullOrWhiteSpace($discovery.token_endpoint)) {
  throw "OIDC discovery response is missing required fields: $($discovery | ConvertTo-Json -Compress)"
}
Write-Output "  [PASS] OIDC Discovery valid (issuer: $($discovery.issuer), token_endpoint: $($discovery.token_endpoint))"

# 4. Check JWKS endpoint
Write-Output "Checking /oauth2/jwks..."
$jwks = Invoke-RestMethod -Uri "$base/oauth2/jwks" -Method Get -TimeoutSec 3
if ($null -eq $jwks.keys -or $jwks.keys.Count -eq 0) {
  throw "JWKS endpoint did not return any cryptographic keys: $($jwks | ConvertTo-Json -Compress)"
}
Write-Output "  [PASS] JWKS endpoint returned $($jwks.keys.Count) active key(s) (kty: $($jwks.keys[0].kty))"

# 5. Check Token endpoint rejection on missing client credentials
Write-Output "Checking /oauth2/token negative contract..."
try {
  $tokenResp = Invoke-WebRequest -Uri "$base/oauth2/token" -Method Post -Body 'grant_type=client_credentials' -ContentType 'application/x-www-form-urlencoded' -ErrorAction Stop
  throw "Token endpoint should have rejected unauthenticated request"
} catch {
  $statusCode = $_.Exception.Response.StatusCode.value__
  if ($statusCode -notin @(400, 401)) {
    throw "Unexpected status code from /oauth2/token without client credentials: $statusCode"
  }
  Write-Output "  [PASS] /oauth2/token correctly rejected unauthenticated request (HTTP $statusCode)"
}

Write-Output "`n=== Full Stack Smoke Test: ALL CHECKS PASSED ==="

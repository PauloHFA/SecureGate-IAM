[CmdletBinding()]
param([ValidatePattern('^[a-z][a-z0-9-]{2,50}$')][string]$ProjectName = 'securegate-iam')
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'LocalEnvironment.ps1')
$projectPath = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$settings = Read-LocalEnvironment (Join-Path $projectPath '.env')
$previousAuth = [Environment]::GetEnvironmentVariable('REDISCLI_AUTH', 'Process')
function Read-RedisAcl([string]$User) {
  $result = & docker compose --env-file (Join-Path $projectPath '.env') -f (Join-Path $projectPath 'infra/docker-compose.yml') -p $ProjectName exec -T -e REDISCLI_AUTH redis redis-cli -e -3 --json --user default ACL GETUSER $User
  if ($LASTEXITCODE -ne 0) { throw 'Nao foi possivel verificar a ACL Redis existente.' }
  return ($result | ConvertFrom-Json)
}
function Get-PasswordHash([string]$Value) {
  $sha = [Security.Cryptography.SHA256]::Create()
  try { return ([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($Value)))).Replace('-', '').ToLowerInvariant() }
  finally { $sha.Dispose() }
}
try {
  [Environment]::SetEnvironmentVariable('REDISCLI_AUTH', $settings.REDIS_PASSWORD, 'Process')
  $users = @(& docker compose --env-file (Join-Path $projectPath '.env') -f (Join-Path $projectPath 'infra/docker-compose.yml') -p $ProjectName exec -T -e REDISCLI_AUTH redis redis-cli -e --raw --user default ACL USERS)
  if ($LASTEXITCODE -ne 0 -or $users.Count -lt 1 -or @($users | Where-Object { $_ -cnotin @('default', 'securegate_metrics') }).Count) { throw 'Usuarios Redis inesperados; nao substituir ACL customizada.' }
  $default = Read-RedisAcl 'default'
  $defaultHash = Get-PasswordHash $settings.REDIS_PASSWORD
  if ($default.flags -notcontains 'on' -or $default.flags -contains 'nopass' -or $default.commands -ne '+@all' -or $default.keys -ne '~*' -or $default.channels -ne '&*' -or @($default.passwords).Count -ne 1 -or $default.passwords[0] -ne $defaultHash -or @($default.selectors).Count -ne 0) { throw 'ACL default divergente da infraestrutura base; nao sobrescrita.' }
  $commands = @('-@all', '+info', '+ping', '+client|setname', '+command|info', '+slowlog|len')
  if ($users -ccontains 'securegate_metrics') {
    $metrics = Read-RedisAcl 'securegate_metrics'
    if ($metrics.flags -notcontains 'on' -or $metrics.flags -contains 'nopass' -or $metrics.keys -ne '' -or $metrics.channels -ne '' -or @($metrics.selectors).Count -ne 0 -or @($metrics.passwords).Count -ne 1 -or @(Compare-Object $commands @($metrics.commands.Split(' ', [StringSplitOptions]::RemoveEmptyEntries))).Count) { throw 'ACL metrics inesperada; nao normalizar permissoes silenciosamente.' }
  }
  $directory = Join-Path $projectPath 'infra/.local'
  [IO.Directory]::CreateDirectory($directory) | Out-Null
  $passwordPath = Join-Path $directory 'redis-metrics-password.json'
  $aclPath = Join-Path $directory 'redis-metrics-users.acl'
  $hasPassword = Test-Path -LiteralPath $passwordPath -PathType Leaf
  $hasAcl = Test-Path -LiteralPath $aclPath -PathType Leaf
  if ($hasPassword -ne $hasAcl) { throw 'Preparacao Redis parcial; revisar arquivos privados antes de repetir.' }
  if ($hasPassword) {
    $mapping = [IO.File]::ReadAllText($passwordPath) | ConvertFrom-Json
    if (@($mapping.PSObject.Properties).Count -ne 1 -or $mapping.PSObject.Properties.Name -cne 'redis://securegate_metrics@redis:6379') { throw 'Mapa de senha Redis invalido; nao sobrescrito.' }
    $password = $mapping.'redis://securegate_metrics@redis:6379'
  } else { $password = New-LocalSecret }
  if ($password -cnotmatch '\A[0-9a-f]{64}\z') { throw 'Senha Redis metrics invalida; nao sobrescrita.' }
  $metricsHash = Get-PasswordHash $password
  $acl = "user default on #$defaultHash ~* &* +@all`nuser securegate_metrics on #$metricsHash resetkeys resetchannels $($commands -join ' ')`n"
  if ($hasAcl) {
    if ([IO.File]::ReadAllText($aclPath) -cne $acl) { throw 'Arquivo ACL Redis divergente; nao sobrescrito.' }
  } else {
    # Sequential CreateNew writes; partial disk failures require explicit operator recovery.
    $json = @{ 'redis://securegate_metrics@redis:6379' = $password } | ConvertTo-Json -Compress
    foreach ($item in @(@{ Path = $passwordPath; Content = $json }, @{ Path = $aclPath; Content = $acl })) {
      $stream = [IO.File]::Open($item.Path, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
      try { $bytes = [Text.UTF8Encoding]::new($false).GetBytes($item.Content); $stream.Write($bytes, 0, $bytes.Length) }
      finally { $stream.Dispose() }
    }
  }
  Write-Output 'Arquivos privados Redis metrics preparados. Aplicar overlay em janela de reinicio; nenhuma ACL ativa foi alterada.'
} finally {
  [Environment]::SetEnvironmentVariable('REDISCLI_AUTH', $previousAuth, 'Process')
  $password = $null
}

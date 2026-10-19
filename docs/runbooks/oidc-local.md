# OIDC local — entrega 4D2

Prepare infraestrutura, habilite Authorization/Bootstrap, carregue `$baseUrl` e `$adminHeaders` e provisione um usuário ACTIVE conforme o [guia PKCE](oauth-pkce-local.md). OIDC usa o mesmo login; o segredo pertence ao backend do cliente.

Desde a 5D1, o cliente OIDC também exige política no alvo `oauth:<client_id>` / ação `issue`. Siga o [guia de políticas na emissão](politicas-tokens-local.md). Se usar ALLOW_LIMITED, preserve `openid` e inclua `profile`/`email` somente quando desejar permitir essas claims, dentro do consentimento do usuário.

## Discovery e cadastro

```powershell
$discovery = Invoke-RestMethod "$baseUrl/.well-known/openid-configuration"
$discovery | Select-Object issuer, authorization_endpoint, token_endpoint, jwks_uri, userinfo_endpoint

$callback = 'http://127.0.0.1:18090/callback'
$registration = @{
  clientId = 'local-oidc-client'
  name = 'Aplicacao OIDC local'
  audience = 'securegate-api'
  scopes = @('api:read', 'profile', 'email')
  redirectUris = @($callback)
  refreshTokens = $true
  oidc = $true
} | ConvertTo-Json
$webClient = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/admin/oauth-clients/browser" `
  -Headers $adminHeaders -ContentType 'application/json' -Body $registration
$webClient.client
```

`oidc: true` registra `openid` automaticamente: não o coloque em `scopes` no cadastro administrativo. `refreshTokens` é independente e opcional; a janela continua sendo de 30 minutos absolutos. O exemplo cria um novo cliente; não atualiza cadastros existentes. A audiência configurada é a da API, enquanto o ID token será destinado a `local-oidc-client`.

Discovery é público quando Authorization está habilitado e usa o issuer configurado. O launcher local o ajusta à porta do servidor. Em produção, configure o endereço HTTPS público correto. O endpoint `/.well-known/oauth-authorization-server` também publica metadados OAuth. Nenhum dos dois exige sessão ou token bootstrap.

## Autorizar com nonce e PKCE

```powershell
$entropy = New-Object byte[] 32
$random = [Security.Cryptography.RandomNumberGenerator]::Create()
try { $random.GetBytes($entropy) } finally { $random.Dispose() }
$verifier = [Convert]::ToBase64String($entropy).TrimEnd('=').Replace('+','-').Replace('/','_')
$sha = [Security.Cryptography.SHA256]::Create()
try { $digest = $sha.ComputeHash([Text.Encoding]::ASCII.GetBytes($verifier)) } finally { $sha.Dispose() }
$challenge = [Convert]::ToBase64String($digest).TrimEnd('=').Replace('+','-').Replace('/','_')
$state = [Guid]::NewGuid().ToString('N')
$nonce = [Guid]::NewGuid().ToString('N')
$clientId = $webClient.client.clientId
$authorizeUrl = "$baseUrl/oauth2/authorize?response_type=code&client_id=$clientId" +
  '&redirect_uri=' + [Uri]::EscapeDataString($callback) +
  '&scope=' + [Uri]::EscapeDataString('openid profile email api:read') +
  '&state=' + $state + '&nonce=' + $nonce +
  '&code_challenge=' + $challenge + '&code_challenge_method=S256'
$authorizeUrl
```

Abra a URL, faça login e autorize os escopos desejados. `openid` identifica a conta; `profile` libera somente nome, `email` libera endereço e `api:read` autoriza o acesso da aplicação. É possível negar o fluxo inteiro. O nonce é obrigatório nesta implementação, com 16–128 caracteres `[A-Za-z0-9._~-]`. Guarde state, nonce e verifier no backend associados à tentativa de login, com uso único.

A aplicação cliente deve receber o callback e verificar o state antes da troca. Como no guia PKCE, a porta de callback pertence ao cliente e não é instalada automaticamente pelo IAM.

```powershell
$authorizationCode = Read-Host 'Codigo recebido, depois de conferir o state'
$basicHeader = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes(
  $webClient.client.clientId + ':' + $webClient.clientSecret))
$clientHeaders = @{ Authorization = 'Basic ' + $basicHeader }
$tokens = Invoke-RestMethod -Method Post -Uri "$baseUrl/oauth2/token" `
  -Headers $clientHeaders -ContentType 'application/x-www-form-urlencoded' `
  -Body @{
    grant_type = 'authorization_code'
    code = $authorizationCode
    redirect_uri = $callback
    code_verifier = $verifier
  }
$tokens | Select-Object token_type, expires_in, scope
```

A resposta contém `access_token`, `id_token` e, neste cadastro, `refresh_token`. Não imprima a resposta inteira. Código expira em dois minutos; ID token e access token em cinco minutos. Ambos são assinados pelo Vault com RS256 e `kid` publicado em JWKS.

Antes de concluir o login do cliente, use sua biblioteca OIDC para validar o ID token: assinatura/JWKS e algoritmo RS256, issuer exato do discovery, audiência igual a `$clientId`, expiração e nonce igual ao `$nonce` guardado. Confira `azp` quando aplicável. Apenas decodificar Base64 não valida o token. O ID token contém UUID em `sub`, `sid` opaco, instante de login em `auth_time` e claims de nome/e-mail conforme o consentimento; não afirma `email_verified`. Use access token para APIs.

## Userinfo, renovação e revogação

```powershell
$userInfo = Invoke-RestMethod -Uri "$baseUrl/userinfo" `
  -Headers @{ Authorization = 'Bearer ' + $tokens.access_token }
$userInfo | Select-Object sub, name
```

Compare o `sub` com o ID token validado. Userinfo devolve somente as claims aprovadas e exige o access token atual do fluxo `openid`; não aceita ID token. Token desconhecido/adulterado/expirado/revogado ou identidade alterada retorna 401; access token sem `openid`, 403. Não cria sessão de navegador e usa `no-store`.

Siga o [guia de refresh/revogação](oauth-refresh-local.md) para renovar, introspectar e revogar. A renovação OIDC retorna novo ID token: valide assinatura, issuer, audiência e expiração novamente. `sub`, `sid` e `auth_time` permanecem; o nonce inicial não é repetido. Não renove em paralelo nem reutilize refresh antigo. Uma redução de escopos para `openid api:read` omite nome/e-mail no novo ID token e em userinfo. Access token anterior deixa de ser encontrado como ativo após a rotação.

Logout local não revoga automaticamente a família de tokens. Userinfo consulta o estado no IAM; revogação em recursos que validam JWT offline continua dependendo da etapa 7 ou da expiração.

## Escopo suportado

Clientes confidenciais, Code com PKCE S256 e os três escopos OIDC documentados. Clientes sem `oidc: true` continuam sem ID token e não podem solicitar `openid`. Client Credentials não recebe claims de identidade. `offline_access`, clientes públicos, registro dinâmico, logout OIDC, JAR, `request_uri`, `claims` e `max_age` não estão disponíveis. Consulte [ADR-009](../adr/009-oidc-discovery-e-userinfo.md), [evidências](../evidencias/etapa-04d2.md) e [roteiro](../PLANO-DE-IMPLEMENTACAO.md).

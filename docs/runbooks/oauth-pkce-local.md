# Login e Authorization Code com PKCE — entrega 4C

Este guia usa um cliente web confidencial. O segredo do cliente deve ficar no backend desse cliente. Não o incorpore em JavaScript de uma SPA. OIDC é opcional desde a [4D2](oidc-local.md); refresh é opcional desde a 4D1, conforme o [guia de renovação](oauth-refresh-local.md).

## Preparar

Na raiz do projeto, com JDK 21 e Docker:

```powershell
.\mvnw.cmd -B -ntp clean verify
.\infra\scripts\Initialize-Local.ps1
docker compose --env-file .env -f infra/docker-compose.yml up -d --wait --wait-timeout 180
.\infra\scripts\Start-IamCore.ps1 -EnableAuthorization -EnableBootstrap
```

Os parâmetros da 4B continuam válidos: Authorization ativa Transit automaticamente. O bootstrap é necessário para provisionar usuários/clientes; depois disso pode ser desabilitado ao reiniciar. O script usa issuer e porta correspondentes, por padrão `http://127.0.0.1:8080`.

Em outro PowerShell, carregar a configuração administrativa:

```powershell
. .\infra\scripts\LocalEnvironment.ps1
$settings = Read-LocalEnvironment (Join-Path (Get-Location) '.env')
$baseUrl = 'http://127.0.0.1:8080'
$adminHeaders = @{ Authorization = 'Bearer ' + $settings.IAM_BOOTSTRAP_TOKEN }
```

Use um usuário ACTIVE cadastrado conforme o [guia de provisionamento](provisionamento-local.md). O login aceita seu e-mail e senha atuais; não há usuário/senha padrão. A criação de usuário não cria um cliente OAuth.

## Cadastrar cliente web

```powershell
$callback = 'http://127.0.0.1:18090/callback'
$registration = @{
  clientId = 'local-web-client'
  name = 'Aplicacao web local'
  audience = 'securegate-api'
  scopes = @('api:read', 'api:write')
  redirectUris = @($callback)
} | ConvertTo-Json
$webClient = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/admin/oauth-clients/browser" `
  -Headers $adminHeaders -ContentType 'application/json' -Body $registration
$webClient.client
```

O segredo fica em `$webClient.clientSecret` e só é retornado na criação. O GET administrativo mostra os metadados básicos; o cadastro não é um endpoint de registro dinâmico público. Duplicatas retornam 409. Esse cliente usa `authorization_code` com Basic Auth e PKCE obrigatório, sem Client Credentials. O exemplo omite `refreshTokens`, portanto não recebe refresh.

Redirects devem ser HTTPS remoto ou HTTP de loopback, com path e sem query/fragmento/userinfo. São aceitas até três URIs. O servidor compara a string completa, incluindo porta, path e barra final. Não há wildcard, correspondência por prefixo ou porta de loopback dinâmica. Clientes máquina a máquina continuam usando o [cadastro da 4B](oauth-client-credentials-local.md).

## Gerar PKCE e iniciar autorização

```powershell
$entropy = New-Object byte[] 32
$random = [Security.Cryptography.RandomNumberGenerator]::Create()
try { $random.GetBytes($entropy) } finally { $random.Dispose() }
$verifier = [Convert]::ToBase64String($entropy).TrimEnd('=').Replace('+','-').Replace('/','_')
$sha = [Security.Cryptography.SHA256]::Create()
try { $digest = $sha.ComputeHash([Text.Encoding]::ASCII.GetBytes($verifier)) } finally { $sha.Dispose() }
$challenge = [Convert]::ToBase64String($digest).TrimEnd('=').Replace('+','-').Replace('/','_')
$state = [Guid]::NewGuid().ToString('N')
$authorizeUrl = "$baseUrl/oauth2/authorize?response_type=code&client_id=local-web-client" +
  '&redirect_uri=' + [Uri]::EscapeDataString($callback) +
  '&scope=' + [Uri]::EscapeDataString('api:read') +
  '&state=' + $state + '&code_challenge=' + $challenge + '&code_challenge_method=S256'
$authorizeUrl
```

Abra essa URL no navegador. Faça login com o e-mail/senha do usuário provisionado. A página seguinte mostra o cliente e os escopos pedidos; autorize os desejados ou negue. Os formulários de login, consentimento e logout incluem tokens CSRF gerenciados pelo servidor. Não crie um POST de consentimento sem esse token.

O consentimento pendente expira em cinco minutos e só pode ser usado pela sessão/usuário que iniciou o fluxo. Um consentimento já concedido pode evitar nova tela para os mesmos escopos. State ausente, PKCE sem S256, desafio inválido, escopo vazio e redirect diferente do cadastro são rejeitados antes do login.

O servidor redireciona para `$callback?code=...&state=...`. O exemplo usa uma porta separada que deve pertencer à aplicação cliente. Sem um listener nessa porta, o navegador pode mostrar erro de conexão; a URL ainda permite conferir o callback em uma demonstração manual. Este projeto não instala automaticamente uma aplicação cliente nessa porta.

## Trocar o código

O backend do cliente deve validar que o state retornado é exatamente o guardado em `$state`. Em uma demonstração manual, confira o state antes de copiar somente o código para `$authorizationCode`:

```powershell
$authorizationCode = Read-Host 'Codigo recebido no callback, depois de conferir o state'
$basicValue = $webClient.client.clientId + ':' + $webClient.clientSecret
$basicHeader = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($basicValue))
$tokens = Invoke-RestMethod -Method Post -Uri "$baseUrl/oauth2/token" `
  -Headers @{ Authorization = 'Basic ' + $basicHeader } `
  -ContentType 'application/x-www-form-urlencoded' `
  -Body @{
    grant_type = 'authorization_code'
    code = $authorizationCode
    redirect_uri = $callback
    code_verifier = $verifier
  }
$tokens | Select-Object token_type, expires_in, scope
```

O código vale dois minutos e tem uso único, inclusive sob duas trocas simultâneas. O verifier deve corresponder ao desafio original e o redirect deve ser o mesmo usado na autorização. O segredo gerado e o ID validado permitem o Basic do exemplo; o cliente deve fazer form encoding dos componentes se usar credenciais com caracteres reservados em outras integrações.

O access token fica em `$tokens.access_token`, vale cinco minutos e é assinado pelo Vault. Contém UUID do usuário em `sub`, audiência do cliente, escopos aprovados, `roles` diretos/herdados e `sid` opaco. Este cadastro não recebe refresh token nem ID token, pois omite as opções `refreshTokens` e `oidc`. O JWKS permanece em `$baseUrl/oauth2/jwks`. Evite publicar tokens, códigos, verifier, respostas de cadastro e capturas com esses valores.

## Sessão e diagnóstico

A sessão tem timeout por inatividade de 15 minutos, cookie HttpOnly/SameSite=Lax e troca de ID após login. `GET /login` com sessão válida permite sair por formulário POST protegido por CSRF. Bloqueio, desativação, mudança de senha ou outra alteração de versão do usuário exigem novo login e impedem trocar códigos criados pelo snapshot antigo.

| Resultado | Verificação |
| --- | --- |
| Mensagem genérica no login | Conferir e-mail/senha e estado ACTIVE por administração autorizada |
| 400 antes do login | Redirect exato, state de 16–128 caracteres, escopos e PKCE S256 |
| 403 no consentimento | CSRF, state pendente, usuário/sessão correspondente e prazo de cinco minutos |
| 404 na página de consentimento | Pedido inexistente, expirado, já processado ou de outra sessão/cliente |
| 400 `invalid_grant` no token | Código expirado/reutilizado, cliente/redirect/verifier incorreto ou identidade alterada |
| 503 `temporarily_unavailable` | Verificar Vault/AppRole; não existe assinatura local de fallback |

Logout termina a sessão local do navegador, sem revogar automaticamente a família de refresh. Tokens já emitidos permanecem verificáveis offline até expirar, e não há promessa de revogação global de códigos anteriores ao logout. Introspecção e revogação explícita estão disponíveis na [4D1](oauth-refresh-local.md), OIDC na [4D2](oidc-local.md) e revogação distribuída continua na etapa 7. A sessão atual é local à instância; Redis/rate limiting vêm na etapa 7.

Consulte [ADR-007](../adr/007-login-pkce-e-consentimento.md), [evidências 4C](../evidencias/etapa-04c.md) e [roteiro](../PLANO-DE-IMPLEMENTACAO.md).

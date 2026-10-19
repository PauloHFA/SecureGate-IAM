# Refresh, introspecção e revogação — entrega 4D1

Use a preparação, usuário ACTIVE e fluxo PKCE do [guia de login](oauth-pkce-local.md). A aplicação deve iniciar com `Start-IamCore.ps1 -EnableAuthorization`; bootstrap é necessário somente para o cadastro. Os três endpoints abaixo são POST, com Basic Auth do cliente proprietário, sem sessão de navegador.

Desde a [5D1](politicas-tokens-local.md), renovar exige política atual no alvo `oauth:<client_id>` / `issue`. MFA exigido deve ser comprovado no sid original do grant; a chamada do backend não precisa de cookie, mas usa esse vínculo persistido. Limites podem reduzir escopos/TTL; escopos removidos não reaparecem na família por omissão de `scope`.

## Habilitar refresh no cadastro

No objeto de cadastro web do guia PKCE, antes de executar o POST, inclua:

```powershell
$registration = @{
  clientId = 'local-refresh-client'
  name = 'Aplicacao web com renovacao'
  audience = 'securegate-api'
  scopes = @('api:read', 'api:write')
  redirectUris = @($callback)
  refreshTokens = $true
} | ConvertTo-Json
$webClient = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/admin/oauth-clients/browser" `
  -Headers $adminHeaders -ContentType 'application/json' -Body $registration
```

Use esse client ID também na URL de autorização. Complete login, consentimento e troca do código conforme o guia PKCE. O consentimento informa a janela de renovação; a resposta `$tokens` inclui `refresh_token`. O padrão sem o campo continua sem refresh. O cadastro atual não atualiza clientes existentes: use um novo client ID. Client Credentials não aceita essa opção.

## Renovar

Após a troca de código, as variáveis `$baseUrl`, `$basicHeader` e `$tokens` já estão definidas pelo guia PKCE:

```powershell
$clientHeaders = @{ Authorization = 'Basic ' + $basicHeader }
$renewed = Invoke-RestMethod -Method Post -Uri "$baseUrl/oauth2/token" `
  -Headers $clientHeaders -ContentType 'application/x-www-form-urlencoded' `
  -Body @{ grant_type = 'refresh_token'; refresh_token = $tokens.refresh_token }
$tokens = $renewed
$tokens | Select-Object token_type, expires_in, scope
```

Guarde o novo par de tokens atomicamente e descarte o refresh anterior. Nunca faça duas renovações simultâneas. Reenviar um refresh já consumido retorna `invalid_grant` e revoga a família inteira, inclusive o token recém-emitido. Falha transitória de assinatura retorna 503 `temporarily_unavailable` e faz rollback do consumo; não troque o token armazenado nesse caso.

O access token dura cinco minutos. A família expira 30 minutos após sua primeira emissão, sem extensão por rotação; depois disso refaça o fluxo PKCE. Alteração da versão da identidade, senha, bloqueio ou desativação também exige nova autenticação. Opcionalmente envie `scope` com um subconjunto dos escopos originalmente aprovados; ampliar retorna `invalid_scope`.

## Consultar e revogar

```powershell
$inspection = Invoke-RestMethod -Method Post -Uri "$baseUrl/oauth2/introspect" `
  -Headers $clientHeaders -ContentType 'application/x-www-form-urlencoded' `
  -Body @{ token = $tokens.access_token }
$inspection | Select-Object active, client_id, scope, exp

Invoke-RestMethod -Method Post -Uri "$baseUrl/oauth2/revoke" `
  -Headers $clientHeaders -ContentType 'application/x-www-form-urlencoded' `
  -Body @{ token = $tokens.refresh_token } | Out-Null

$afterRevocation = Invoke-RestMethod -Method Post -Uri "$baseUrl/oauth2/introspect" `
  -Headers $clientHeaders -ContentType 'application/x-www-form-urlencoded' `
  -Body @{ token = $tokens.access_token }
$afterRevocation.active
```

O último valor deve ser `False`. O proprietário pode consultar access ou refresh atual; token desconhecido, histórico ou de outro cliente retorna somente `active: false`. Revogar um access token da família ou qualquer refresh conhecido revoga a família; repetir é seguro. Token desconhecido/de outro cliente também retorna 200, sem alteração. Ausência de autenticação retorna 401; ausência de `token`, com autenticação válida, retorna 400.

Os mesmos endpoints atendem Client Credentials usando as credenciais do cliente máquina; a revogação afeta o access token apresentado. Não publique tokens, segredo, cabeçalho Basic ou respostas completas. Respostas OAuth usam `Cache-Control: no-store`.

## Limites

Introspecção consulta o estado no servidor. Um recurso que verifica somente assinatura/expiração do JWT pode continuar aceitando-o até expirar; a etapa 7 implementará propagação da revogação. Logout local não revoga refresh automaticamente. OIDC, discovery, ID token e userinfo estão disponíveis na [4D2](oidc-local.md). Veja [ADR-008](../adr/008-refresh-rotacao-e-revogacao.md) e [evidências](../evidencias/etapa-04d1.md).

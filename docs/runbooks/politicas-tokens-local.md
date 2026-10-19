# Políticas na emissão de tokens de usuário — 5D1

Authorization Code e refresh agora exigem política correspondente. Sem ela, a troca retorna `invalid_grant`. Este guia prepara um cliente, aplica limites e exige MFA na sessão original.

## Preparar uma política

Inicie a aplicação com Authorization/Transit e bootstrap, conforme os guias [PKCE](oauth-pkce-local.md) e [MFA](mfa-local.md). Reutilize `$baseUrl` e `$adminHeaders` do guia PKCE; use o ID real do cliente registrado. O exemplo abaixo permite somente leitura e access tokens de até 60 segundos:

```powershell
$clientId = 'local-web-client'
$policyBody = @{
  name = "emissao-$clientId"
  priority = 100
  enabled = $true
  document = @{
    schemaVersion = 1
    resource = "oauth:$clientId"
    action = 'issue'
    effect = 'ALLOW_LIMITED'
    rolesAnyOf = @()
    allowedScopes = @('api:read')
    maxAccessTokenTtlSeconds = 60
  }
} | ConvertTo-Json -Depth 8
$policy = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/admin/policies" `
  -Headers $adminHeaders -ContentType 'application/json' -Body $policyBody
```

Esse exemplo permite qualquer identidade ativa/atual para esse cliente, dentro do escopo indicado. Para exigir papéis, preencha `rolesAnyOf` com papéis realmente atribuídos. Não existe ALLOW implícito. Para um cliente OIDC que solicita `openid`, inclua também `openid` na lista permitida. `profile`/`email` só resultam em claims pessoais se permanecerem permitidos.

Cadastre uma política uma única vez. Para atualizar, use `PUT /api/admin/policies/{id}` com a versão atual conforme o [guia de políticas](politicas-local.md). Sem necessidade de limites, uma política ALLOW usa `allowedScopes = @()` e omite TTL; ela mantém os escopos previamente consentidos. A prioridade ordena avaliação, sem anular DENY nem limites de outras políticas correspondentes.

## Conferir o resultado

Faça a autorização e troque o código conforme o guia PKCE. Compare `scope` e `expires_in` da resposta com as permissões consentidas e a política. O token não recebe escopos adicionais. Em OIDC, retirar `profile`/`email` também retira nome/e-mail do ID token e do userinfo. O ID token mantém audiência/validade próprias; limite de 60 segundos aplica-se ao access token.

Uma política DENY correspondente impede a emissão. Desabilitar todas as políticas correspondentes também nega. Alterar uma política depois de gerar o código é observado na troca. Alterar depois de emitir um JWT não revoga esse JWT automaticamente.

Na renovação, a política é reavaliada. Um refresh pode perder escopos e validade; escopos removidos não são recuperados ao omitir `scope` numa renovação posterior. Faça nova autorização para solicitá-los novamente. A rotação e o prazo absoluto da família continuam conforme o [guia de refresh](oauth-refresh-local.md).

## Exigir MFA

Crie/atualize outra política no mesmo alvo `oauth:<client_id>` / `issue`, com efeito `ALLOW_WITH_MFA`, `rolesAnyOf` conforme a necessidade, `allowedScopes` vazio e sem TTL. Ela se combina com ALLOW_LIMITED existente. Consulte o guia administrativo para IDs/versões.

Antes de autorizar, abra `/login` e `/mfa` no **mesmo navegador** e comprove TOTP/recuperação conforme o [guia MFA](mfa-local.md). Depois inicie a autorização OAuth nessa sessão. O sid é vinculado ao código; prova feita em outro navegador não serve. O access token termina, no máximo, junto com a prova utilizada.

A [5D2](desafio-mfa-local.md) direciona o navegador para MFA antes do consentimento quando necessário e oferece retorno protegido. A emissão continua reavaliando: se a troca retornar `invalid_grant` por falta de prova, use `/mfa` na sessão original e tente novamente enquanto o código estiver válido (dois minutos). Após vencer esse prazo, inicie nova autorização. No refresh, comprove de novo na sessão original se a prova tiver expirado. Se houve logout ou novo login, o sid antigo foi encerrado: faça um novo fluxo OAuth.

## Risco e erros

Condições ABAC usam `resource = oauth:<client_id>` e os sinais reais da sessão de origem. Classifique esse recurso e as redes/dispositivos conforme o [guia de coleta](coleta-risco-local.md). A requisição do backend ao endpoint de token não fornece contexto confiável por parâmetro; não use `simulate` como prova.

| Resultado | Interpretação |
| --- | --- |
| 200 | Token limitado pelos escopos aprovados, políticas e prova exigida |
| 400 `invalid_grant` | Grant inválido, política negada/ausente ou MFA insuficiente; resposta não diferencia os motivos |
| 400 `invalid_scope` | Escopo pedido no refresh não pertence ao grant atual |
| 503 `temporarily_unavailable` | Dependência ou auditoria indisponível; não há emissão irrestrita como fallback |

Falhas de emissão não consomem parcialmente código/refresh. Repetir após **sucesso** continua sendo replay e mantém as proteções de revogação da família.

Build de aceite:

```powershell
.\mvnw.cmd -B -ntp clean verify
```

Verificação de senha e Client Credentials não recebem essa política de identidade humana. A navegação tem desafio integrado desde a 5D2; proteção por operação nos recursos continua na 5D3/enforcer. Veja [ADR-015](../adr/015-politicas-na-emissao-de-tokens.md), [evidências históricas](../evidencias/etapa-05d1.md) e [roteiro](../PLANO-DE-IMPLEMENTACAO.md).

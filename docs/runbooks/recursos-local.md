# Recursos protegidos por operação — 5D3

O recurso de demonstração `GET /api/resources/account` exige access token de usuário e uma política própria. A política de emissão `oauth:<client_id>` / `issue` não concede acesso ao recurso.

## Preparação

Siga os guias [PKCE](oauth-pkce-local.md) e [políticas na emissão](politicas-tokens-local.md), com Authorization/Transit habilitados. Cadastre o cliente web com audiência `securegate-api` e escopo `api:read`. Esse é o valor padrão da API. Para usar outra audiência, defina `IAM_RESOURCES_AUDIENCE` no processo que inicia a aplicação, correspondendo ao cadastro do cliente. O script de inicialização herda essa variável; não é necessário alterar `.env`.

Reutilize `$baseUrl` e `$adminHeaders` do guia PKCE. Crie a política do recurso:

```powershell
$resourcePolicy = @{
  name = 'leitura-conta-local'
  priority = 100
  enabled = $true
  document = @{
    schemaVersion = 1
    resource = 'account:self'
    action = 'read'
    effect = 'ALLOW_LIMITED'
    rolesAnyOf = @()
    allowedScopes = @('api:read')
    maxAccessTokenTtlSeconds = 60
  }
} | ConvertTo-Json -Depth 8
$createdPolicy = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/admin/policies" `
  -Headers $adminHeaders -ContentType 'application/json' -Body $resourcePolicy
```

O exemplo atende qualquer identidade ativa com grant válido. Para restringir por papel atual, configure `rolesAnyOf`; para risco, use a gramática v2 e os sinais reais documentados em [coleta de risco](coleta-risco-local.md). `DENY` correspondente prevalece mesmo sobre uma política ALLOW.

Após concluir login, consentimento e troca PKCE, use o access token mantido em memória. Se o retorno da troca estiver em `$tokens`:

```powershell
$resourceHeaders = @{ Authorization = "Bearer $($tokens.access_token)" }
$account = Invoke-RestMethod -Method Get -Uri "$baseUrl/api/resources/account" `
  -Headers $resourceHeaders
$account
```

A resposta contém `sub`, `scope` e `expires_at`. Não envie ID token, refresh token, segredo do cliente ou token administrativo. Não coloque tokens em URLs ou arquivos versionados. Cookies não substituem o Bearer.

## Limites e MFA

O escopo obrigatório é `api:read`. A interseção não pode adicionar escopos ao grant. O prazo da política é contado a partir do `iat` do token, sem reiniciar a cada chamada; um token emitido há 70 segundos não atende uma política de 60 segundos, mesmo que seu `exp` original ainda esteja no futuro.

Para exigir MFA, crie/edite a política com `effect = ALLOW_WITH_MFA`, conforme a API de versionamento do [guia de políticas](politicas-local.md). Sem prova vigente, o recurso retorna 403 `mfa_required`. Abra `/mfa` na sessão de navegador que originou o grant e siga o [guia MFA](mfa-local.md); completar MFA em outro navegador não comprova o sid original. Repita a chamada Bearer após a verificação. A API não abre fluxo interativo nem aceita `mfa=true`, sid ou contextId fornecidos na chamada.

| Teste | Resultado esperado |
| --- | --- |
| Sem token, token adulterado, audiência errada, ID token | 401 |
| Sem política do recurso ou DENY atual | 403 |
| Política remove `api:read` ou prazo atual acabou | 403 |
| MFA exigido sem prova original | 403 `mfa_required` |
| Prova original válida + política permite | 200 |
| Token substituído por refresh ou revogado | 401 |
| Versão da identidade mudou | 401 |
| Banco/política/prova/auditoria falha | 503, sem dados |

## Auditoria e próximos passos

A permissão registra `resource.access.allowed`, sem token ou códigos. O registro contém ator, alvo/ação, correlação, políticas/versões, risco, escopos, exigência MFA e prazo. Recusas têm erros HTTP sanitizados e não prometem auditoria durável de toda tentativa rejeitada.

Essa API consulta o estado local online; não substitui o enforcer para recursos externos. Logout encerra a prova MFA da sessão, mas não revoga automaticamente todo access token sem exigência MFA. Consumo de eventos, Redis, bloqueios distribuídos e a medição de propagação ficam para as etapas 6/7, conforme o [contrato da ADR-017](../adr/017-autorizacao-por-operacao-nos-recursos.md).

Validação reproduzível: `mvnw.cmd -B -ntp clean verify`, com Docker disponível para PostgreSQL/Vault temporários. Ver [evidências](../evidencias/etapa-05d3.md).

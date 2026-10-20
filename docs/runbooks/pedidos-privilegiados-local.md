# Pedidos de acesso privilegiado — 9B1

Esta entrega registra autorização de pedidos. **Não emite credenciais nem libera conexão ao banco**; essa integração será feita na 9B2. Ver [ADR-038](../adr/038-pedidos-de-acesso-privilegiado.md) e [evidências](../evidencias/etapa-09b1.md).

## Preparar

Usar Java 21 e Docker. Os testes usam containers descartáveis. Para aplicar ao ambiente persistente, parar os runtimes e executar as migrações como proprietário, reaplicando o perfil restrito antes de iniciar o modo dinâmico:

```powershell
.\mvnw.cmd -B -ntp clean verify
.\infra\scripts\Migrate-Database.ps1
.\infra\scripts\Provision-RuntimeDatabase.ps1
.\infra\scripts\Start-IamCore.ps1 -EnablePam -EnableBootstrap
```

`-EnablePam` ativa Authorization e Transit, exige o bootstrap Vault existente e não ativa o administrador por conta própria. Pode ser combinado com `-EnableVaultDatabase` após a [preparação do Database Engine](vault-database-local.md). A flag de aplicação é `IAM_PAM_ENABLED=true`; o padrão é false. Não habilitar PAM sem Authorization/Transit. O build não migra a stack persistente.

## Configurar autorização

Seguir [provisionamento](provisionamento-local.md), [PKCE](oauth-pkce-local.md), [políticas](politicas-local.md) e [MFA](mfa-local.md):

1. Criar papel `PAM_OPERATOR` e atribuí-lo somente ao usuário de teste autorizado.
2. Registrar cliente web confidencial com audiência `securegate-api`, escopo `pam:request` e callback permitido. A política `oauth:<clientId>` / `issue` deve permitir esse escopo ao papel apropriado.
3. Criar a política de operação abaixo via `/api/admin/policies`. Não há política permissiva criada automaticamente.
4. Fazer login e aprovar `pam:request` por Authorization Code + PKCE S256. Concluir MFA **na mesma sessão** que originou o access token. Usar o access token; ID token, cookie isolado e token bootstrap não autorizam PAM.

Documento de política sugerido para teste:

```json
{
  "schemaVersion": 1,
  "resource": "database:diagnostics",
  "action": "request",
  "effect": "ALLOW_LIMITED",
  "rolesAnyOf": ["PAM_OPERATOR"],
  "allowedScopes": ["pam:request"],
  "maxAccessTokenTtlSeconds": 120
}
```

Um `DENY` aplicável impede a operação. MFA continua obrigatório mesmo se outra política permitir `ALLOW`. A audiência pode ser alterada pela configuração de recursos existente, mas precisa coincidir com a do cliente/token.

## Solicitar, consultar e revogar

No backend do cliente OAuth ou em uma sessão de teste, definir `$accessToken` com o access token obtido no fluxo anterior. Não imprimir/gravar tokens ou justificativas com credenciais:

```powershell
$baseUrl = 'http://127.0.0.1:8080'
$headers = @{ Authorization = 'Bearer ' + $accessToken }
$body = @{ justification = 'Investigar incidente INC-123'; ttlSeconds = 120 } | ConvertTo-Json
$request = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/pam/requests" -Headers $headers -ContentType 'application/json' -Body $body
Invoke-RestMethod -Uri "$baseUrl/api/pam/requests/$($request.id)" -Headers $headers
Invoke-RestMethod -Method Delete -Uri "$baseUrl/api/pam/requests/$($request.id)" -Headers $headers
```

POST retorna 201 e metadados `id`, `target`, `status`, `createdAt`, `expiresAt`, `revokedAt`. Não retorna justificativa ou segredo. TTL solicitado: 30–300 segundos; a validade efetiva pode ser menor por política, idade do token ou prazo da prova MFA. Ler o pedido não renova a validade.

GET/DELETE retornam 200 para o proprietário autorizado. Repetir DELETE conserva `REVOKED`, ou `EXPIRED` se o prazo já tiver terminado, sem novo evento. POST não é idempotente: evitar repetição automática indiscriminada. IDs de terceiros/inexistentes recebem 404 depois da autorização.

Erros: 400 para corpo inválido; 401 para bearer ausente/inválido; 403 para escopo/política ou prova MFA ausente; 429 para bloqueio de requisições; 503 quando uma dependência da autorização falha. `mfa_required` exige nova prova na sessão original. Falha transacional/auditoria recebe resposta genérica e não confirma pedido. A perda de permissões também impede consultar/revogar via usuário; não existe credencial emitida nesta etapa e a expiração continua limitada.

## Auditoria e limites

Pedido e `pam.request.authorized` são atômicos com a Outbox. Revogação e `pam.request.revoked` também. Os envelopes não incluem justificativa, senha ou token. A justificativa permanece em `iam_authorization.privileged_requests`, protegida pelas permissões do banco; não inserir dados secretos. Rotinas de expurgo e auditoria de expiração pertencem à 9C.

Nesta fase `AUTHORIZED` é um registro histórico de autorização; não usar o ID como senha, bearer ou prova suficiente para um futuro endpoint de emissão. A 9B2 deverá reautorizar, consumir o pedido e vincular o lease ao ciclo real do Vault. Não reutilizar o emissor/runtime do banco para conceder acesso humano. Produção ainda exige TLS e hardening documentados nas etapas anteriores.

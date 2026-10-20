# Credenciais PAM no Vault — 9B2

Ver [ADR-039](../adr/039-emissao-pam-no-vault.md) e [evidências](../evidencias/etapa-09b2.md). O alvo é diagnóstico restrito do banco, sem acesso às tabelas de usuários/auditoria e sem superusuário.

## Preparar

Com Java 21, Docker e infraestrutura local saudável, parar os processos PAM antes de reaplicar o provisionamento:

```powershell
.\mvnw.cmd -B -ntp clean verify
docker compose --env-file .env -f infra/docker-compose.yml run --rm vault-init
.\infra\scripts\Migrate-Database.ps1
.\infra\scripts\Provision-PamDatabase.ps1
.\infra\scripts\Start-IamCore.ps1 -EnablePamCredentials -EnableBootstrap
```

O migrador aplica V19 e reaplica permissões runtime. O provisionador cria a visão/grupo de diagnóstico e o emissor dedicado, configura pam-database, os cinco papéis de TTL e rotaciona a senha do emissor no Vault sem gravá-la localmente. Não modifica o emissor do runtime. É um script administrativo: verificar o ambiente antes de executá-lo. Não basta carregar pam-role.json diretamente: o provisionador substitui PAM_TTL_SECONDS pelo inteiro de cada papel.

vault-init entrega iam-pam-role-id/iam-pam-secret-id em infra/.local, com ACL separada e token limitado a cinco minutos. `-EnablePamCredentials` ativa PAM, Authorization e Transit; bootstrap só é ativado se explicitado. Pode ser combinado com o modo Vault Database do runtime após seu provisionamento próprio. Defaults de aplicação: IAM_PAM_CREDENTIALS_ENABLED=false; IAM_PAM_VAULT_ADDRESS herda IAM_VAULT_ADDRESS; IAM_PAM_VAULT_ROLE_ID_FILE e IAM_PAM_VAULT_SECRET_ID_FILE apontam aos arquivos separados. Não usar arquivos do runtime para o emissor PAM.

O banco deve exigir senha/SCRAM para o caminho de acesso humano; trust contorna a expiração por senha. O ambiente local sem TLS/Vault persistente não é configuração de produção.

## Autorizar e emitir

Seguir o [guia 9B1](pedidos-privilegiados-local.md) e o [fluxo PKCE](oauth-pkce-local.md). O cliente confidencial e a política de emissão OAuth devem permitir **pam:request e pam:issue**. Obter ambos no consentimento e comprovar MFA na sessão original. Criar duas políticas, com papel PAM_OPERATOR atribuído ao operador autorizado:

| Recurso | Ação | Escopo | Efeito sugerido |
| --- | --- | --- | --- |
| database:diagnostics | request | pam:request | ALLOW_LIMITED, até 120 s |
| database:diagnostics | issue | pam:issue | ALLOW_LIMITED, até 120 s |

Usar o documento da 9B1 alterando action/allowedScopes para a segunda política. DENY prevalece. O escopo de pedido isolado não emite credencial; MFA também é obrigatório na emissão mesmo com ALLOW.

Com `$accessToken` obtido pelo backend do cliente e sem imprimir seu valor:

```powershell
$baseUrl = 'http://127.0.0.1:8080'
$headers = @{ Authorization = 'Bearer ' + $accessToken }
$body = @{ justification = 'Investigar incidente INC-123'; ttlSeconds = 120 } | ConvertTo-Json
$request = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/pam/requests" -Headers $headers -ContentType 'application/json' -Body $body
$credential = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/pam/requests/$($request.id)/credentials" -Headers $headers
$metadata = Invoke-RestMethod -Uri "$baseUrl/api/pam/requests/$($request.id)/credentials" -Headers $headers
```

A resposta 201 da emissão contém requestId, username, password e expiresAt. Não imprimir `$credential`, gravá-la em arquivo ou colocar a senha em argumento de processo/log. O GET retorna somente estado/prazo, sem senha nem lease ID. Configurar o cliente PostgreSQL para o host/porta/banco locais e usar a credencial em memória; a consulta permitida é `SELECT * FROM securegate_diagnostics.database_health`. A senha expira; o prazo efetivo será de um dos intervalos 10/30/60/120/240 s que caiba no orçamento restante. Para pedido de 120 s, normalmente o intervalo escolhido é 60 s após descontar a margem.

Emissão concorrente/repetida recebe 409 e não devolve outro segredo. Resposta perdida ou falha ambígua não permite recuperar a senha: revogar o pedido anterior e criar outro. Não repetir POST automaticamente como se fosse uma consulta. 403 também pode indicar prova/política alterada durante o I/O; 503 sinaliza indisponibilidade ou capacidade de emissão ocupada. Um 503 após consumo pode ser seguido por 409; consultar metadados e revogar antes de tentar outro pedido.

## Revogar e recuperar

O proprietário com autorização corrente usa DELETE do pedido. Se ele perdeu a política, o operador bootstrap pode revogar administrativamente:

```powershell
Invoke-RestMethod -Method Delete -Uri "$baseUrl/api/pam/requests/$($request.id)" -Headers $headers
# Alternativa administrativa: $adminHeaders é configurado pelo guia de provisionamento.
Invoke-RestMethod -Method Post -Uri "$baseUrl/api/admin/pam/requests/$($request.id)/revoke" -Headers $adminHeaders
```

202 administrativo confirma revogação lógica/limpeza pendente, não encerramento físico. Estados da emissão: ISSUING, ISSUED, FAILED, REVOKING, REVOKED. O worker grava REVOKING e repete até Vault aceitar a revogação; não remove o lease em falha. Reiniciar o IAM retoma leases conhecidos; claims sem conclusão após trinta segundos viram FAILED, sem reemitir.

Observar `iam.pam.cleanup.failures` nas métricas administrativas. Se REVOKING persistir, verificar Vault, arquivos AppRole, emissor/ACL e saúde PostgreSQL; não expor tokens/respostas do Vault ao investigar. Reaplicar provisionamento somente em janela de manutenção. Se Vault dev perder estado, revisar logins pam_* administrativamente: o novo Vault não conhece os leases antigos. Não apagar metadados nem usar force revoke para ocultar falha de limpeza.

**Sessões já abertas podem ultrapassar o TTL enquanto Vault estiver indisponível.** VALID UNTIL controla novas autenticações por senha, não a sessão ativa. Na retomada, a revogação termina as sessões. idle_session_timeout/idle_in_transaction_session_timeout/statement_timeout não constituem prazo absoluto de uma sessão mantida ativa. Este comportamento é medido pelos testes e não deve ser apresentado como garantia absoluta de acesso temporário sob falha.

Emissão/falha/revogação geram eventos sem segredo/username/lease ID/justificativa. A [9C1](auditoria-pam-local.md) acrescenta fatos de expiração lógica e recusas autenticadas; a [9C2](recuperacao-pam-local.md) acrescenta retry/métricas, e a [9C3](jornada-pam-integrada-local.md) valida o fluxo com reinício de lease conhecido e auditoria central. Não há retenção automática de metadados nessas entregas. Não houve provisionamento automático da stack persistente pelo build.

# ADR-039 — Emissão PAM no Vault

- Data: 20/10/2026
- Estado: aceita para a entrega local 9B2

## Decisão

Entregar `POST /api/pam/requests/{id}/credentials` como emissão única, opcional, após a fundação 9B1. Exigir access token humano do Authorization Code, audiência correta, identidade/grant atuais, escopo **pam:issue**, MFA do sid original e política `database:diagnostics` / `issue`. A política `request` não permite emitir. Reavaliar antes da aquisição e novamente após a chamada ao Vault, antes de confirmar a entrega. Não aceitar caminho Vault, papel SQL ou usuário no corpo.

V19 cria `privileged_issuances`, cuja chave primária é o pedido. Uma transação curta bloqueia o pedido e grava ISSUING; nenhuma transação fica aberta durante HTTP. Concorrentes/repetições recebem 409, sem segunda emissão. Após reautorizar, outra transação bloqueia o pedido, confirma ausência de revogação e prazo, grava lease/prazo/ISSUED e auditoria/Outbox. Somente então a senha pode sair na resposta. O DTO tem toString redigido e a resposta usa no-store.

Senha e token Vault não são persistidos. O lease ID fica no banco somente para revogação e não aparece na API/auditoria. GET na mesma rota retorna apenas metadados. Não há redelivery nem garantia de recebimento pelo cliente: resposta perdida continua consumida. Primeiro revogar o pedido/lease anterior antes de criar outro. Falha de auditoria impede entrega e agenda limpeza do lease conhecido. Falha ambígua sem lease conhecido permanece consumida; claims sem conclusão após trinta segundos viram FAILED, sem reemitir. Uma conclusão tardia não pode substituir FAILED e deve compensar a emissão.

## Isolamento e prazo

Mount `pam-database`, emissor SQL `securegate_vault_pam`, grupo NOLOGIN `securegate_pam_diagnostics` e AppRole `iam-pam` separados do runtime. O alvo só concede SELECT em `securegate_diagnostics.database_health`, uma visão dos contadores do banco atual; não concede acesso a identidades, auditoria, tabelas de negócio, propriedade ou administração do banco. Não há credencial de superusuário entregue ao solicitante. O emissor CREATEROLE/pg_signal_backend continua uma identidade administrativa sensível; não é isolamento completo de um cluster compartilhado.

Os papéis diagnostics-10/30/60/120/240 têm TTL padrão/máximo fixos. Escolher o maior que caiba no prazo restante do pedido e permit, descontando cinco segundos para I/O. Confirmar novamente após obter a credencial; se o prazo devolvido ultrapassar o limite corrente, não entregar. Assim a credencial pode durar menos que o TTL solicitado; não renovar leases humanos. O template SQL recebe apenas o TTL numérico da lista fixa e define VALID UNTIL por clock_timestamp mais esse intervalo, sem depender de margens internas do plugin. Autenticação PostgreSQL deve verificar senha, como SCRAM; trust não serve para demonstrar essa expiração.

Cada aquisição usa token AppRole próprio, validado com TTL de até cinco minutos, sem renovação pelo aplicativo. Em falha, revogar o token pai por melhor esforço; em sucesso, fechar o cliente e esquecer o token, permitindo a expiração limitada no Vault. Isso preserva o lease entregue sem guardar outro segredo. Uma emissão interrompida antes de persistir seu lease pode deixar credencial órfã até os prazos do banco/Vault; ela não foi entregue por esta API. SecretID local continua protegido por arquivo, com o modelo reutilizável do bootstrap existente.

ACL permite somente ler diagnostics-* e revogar leases desse prefixo, com sync=true e sem lease_id no corpo. Não permite KV, Transit, configuração, runtime ou renovação. O perfil do IAM pode inserir/consultar a tabela de emissão e atualizar estado/lease/prazo; não pode apagar nem substituir o request_id.

## Revogação e recuperação

DELETE do pedido pelo proprietário conserva a autorização corrente da 9B1. POST administrativo `/api/admin/pam/requests/{id}/revoke` usa o bootstrap administrativo existente, inclusive quando o usuário perdeu sua política, e retorna 202 para a revogação lógica. Não é confirmação de encerramento físico da sessão.

Worker serial verifica até dez itens a cada 500 ms: claims abandonados, leases vencidos e pedidos revogados. Registra REVOKING antes do RPC, repete falhas sem remover o lease e finaliza REVOKED com auditoria/Outbox condicionais. Uma falha não impede os outros itens do lote. Reinício retoma metadados pendentes, sem precisar do segredo original. Duplicatas entre instâncias podem repetir o RPC idempotente, mas não a transição/evento final. Quatro emissões simultâneas por processo limitam o trabalho em voo; isto não é limite global de leases ou SLA de disponibilidade.

**Limite:** VALID UNTIL impede autenticações por senha após o prazo; não termina uma sessão já autenticada. Se Vault estiver indisponível, uma sessão ativa pode persistir além do TTL. Timeouts de statement/ociosidade reduzem exposição, mas não são prazo absoluto de sessão. Retomada do Vault/worker revoga login e termina sessões. Não apresentar esta entrega como controle absoluto de sessão sob falha, nem conceder acesso amplo usando esse modelo sem essa decisão operacional.

Eventos pam.credential.issued/failed/revoked contêm somente requestId, além do envelope comum. Não incluem senha, token, lease ID, username ou justificativa. `iam.pam.cleanup.failures` sinaliza falha do ciclo. A 9C ainda deve completar auditoria de recusas/expiração e recuperação operacional integrada, retenção e observabilidade. KV do enforcer e hardening de produção permanecem pendentes.

Referências: [guia](../runbooks/credenciais-pam-local.md), [evidências](../evidencias/etapa-09b2.md), [ADR-038](038-pedidos-de-acesso-privilegiado.md).

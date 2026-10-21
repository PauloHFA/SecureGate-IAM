# Evidências — 10B3c1 contexto durável no outbox

- Data: 21/10/2026, UTC−03.
- Base: 32f3bfa; branch feat/outbox-trace-context.
- Estado: implementação e validação locais concluídas; incluída neste commit; push/CI remota pendentes.

## Alterações

V23 nullable/constraint/trigger imutável, fornecedor de contexto opcional no adapter JDBC, captura do span corrente via bootstrap e Claim com metadado separado. Sem mudança de JSON/schema v1 de SecurityEvent, headers AMQP, consumidores, ACLs/grants ou stack persistente/.env. A 10B3c foi dividida em c1 persistência, c2 publicação e c3 consumo; c2/c3 propostas, pendentes. [ADR-060](../adr/060-contexto-duravel-no-outbox.md), [operação](../runbooks/tracing-outbox-local.md).

## Seleção direcionada

```powershell
.\mvnw.cmd -B -ntp -pl iam-core -am spotless:apply '-Dtest=OutboxTraceParentTest,OutboxPublisherTest,RabbitEventTransportTest,IamArchitectureTest,ArchitectureRulesTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=OutboxTraceContextIT,HttpServerTracingIT,OutboxPublicationIT,RuntimeDatabasePrivilegesIT' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djacoco.skip=true' verify
```

BUILD SUCCESS às **16:04:34 UTC−03**, **1 min 19 s**. **26 unitários/arquitetura + 57 integração**, zero falhas/erros/skips. OutboxTraceContextIT (5) usa PostgreSQL real, migra uma base separada V22 com evento existente para V23, verifica parent NULL/payload intacto, rollback audit/outbox, sanitização, restrições SQL/trigger e recuperação após retry/nova instância de store. HttpServerTracingIT (5) acrescenta captura a partir de requisição servlet real. OutboxPublicationIT (12) mantém publicação RabbitMQ real; RuntimeDatabasePrivilegesIT (35) inclui recusa de UPDATE na coluna nova. A seleção pula JaCoCo e se sobrepõe ao build completo.

## Build completo

Comando: `.\mvnw.cmd -B -ntp verify`.

A primeira execução detectou três expectativas históricas: IamCoreIT e PartitionedAuditIT ainda esperavam schema 22; PolicyAdministrationIT esperava 17 migrações depois de V5. O banco alcançou V23, e as expectativas passaram a 23 e 18, mantendo as demais asserções de preservação de dados e idempotência. A tentativa terminou com exit 1; não é validação aprovada. As verificações específicas novas passaram nessa tentativa.

A repetição completa final terminou com **BUILD SUCCESS às 16:21:51 UTC−03**, **10 min 7 s**, exit code nativo **0**. **818 testes**, zero falhas/erros/skips: security-events 43; IAM 400 unitários + 298 integração; enforcer 44 unitários + 22 integração; fim a fim 11. Dois gates JaCoCo atendidos; arquitetura incluída. Não houve edição de fonte após o início dessa repetição, nem redução de seleção/gates. Contagens da seleção direcionada e das tentativas se sobrepõem e não devem ser somadas.

Nenhum timeout de encerramento do fork apareceu nesta execução final; não equivale a resolver a ocorrência histórica de Hikari. Smokes opt-in de observabilidade pelo nome não foram executados. A integração RabbitMQ funcional passou, sem alegar tracing AMQP ainda não implementado.

Verificados **1.327 links locais** em README/docs/infra README sem destino ausente, arquivos alterados/novos em UTF-8 sem BOM/whitespace final e git diff --check. Logs locais ignorados: .firecrawl/etapa10b3c1-targeted.log, .firecrawl/etapa10b3c1-verify.log (tentativa falha) e .firecrawl/etapa10b3c1-final.log (aprovada).

## Limites

Novo binário exige V23 mesmo sem tracing; não aplicada à stack persistente. Sem novo grant; SELECT/INSERT de tabela abrangem a coluna. Reconstrução do store não é reinício de JVM. Nenhum parent foi publicado em headers AMQP ou restaurado no consumidor nesta entrega. Não prova tracing fim a fim, continuidade async, backend/retensão centralizada ou capacidade. A ressalva histórica de shutdown Hikari permanece como diagnóstico pendente, não corrigido por esta mudança.

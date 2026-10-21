# Contexto durável no outbox — 10B3c1

## Preparação e implantação

Usar JDK 21/Wrapper e concluir a preparação do banco já documentada. O schema atual passa a **V23**, aditivo. Compilar o JAR antes da migração. A captura usa os perfis http-tracing/structured-logs da [etapa HTTP](tracing-http-local.md), sem nova flag/exporter.

1. Planejar a migração e o lock ALTER TABLE com a identidade de migração; não usar login runtime para DDL.
2. Parar/atualizar o runtime conforme a janela do ambiente. Executar o procedimento de migração existente antes do novo binário, inclusive com tracing desligado.
3. Em ambiente local já preparado, o comando de migração é o abaixo. Ele altera o banco configurado no .env; não foi executado contra a stack persistente nesta entrega.
4. Conferir Flyway em V23 e existência da coluna nullable/constraint/trigger. Depois iniciar IAM com os parâmetros/identidades habituais; em modo de credenciais dinâmicas, Flyway continua separado.
5. Sem tracer/span, novos eventos têm NULL. Sob tracing HTTP, cada append captura o contexto corrente na transação do evento.

```powershell
# Operação sobre o banco configurado; executar somente na janela de migração planejada:
.\infra\scripts\Migrate-Database.ps1
```

SELECT/INSERT da identidade runtime continuam cobrindo a tabela; não conceder UPDATE(trace_parent). Não é necessário alterar RabbitMQ, Redis ou Vault. Não imprimir valores de tokens/headers ao diagnosticar eventos.

## Contrato de persistência

trace_parent tem até 55 caracteres: 00-<32 hex>-<16 hex>-00/01, letras minúsculas, IDs não nulos; NULL permitido. Não há JSON de headers, baggage, tracestate, URL, body ou credencial nesse campo. correlationId é outro identificador; o envelope SecurityEvent v1 permanece intacto. IDs de trace podem ser escolhidos pelo cliente e não autorizam operações. Não colocar dados pessoais/segredos neles nem utilizá-los como labels de métricas.

O parent é imutável após o INSERT. ACK/retry/lease/quarentena continuam alterando somente estado de entrega; retries recuperam o valor original, sem substituí-lo pelo contexto do worker. O trigger recusa mudança inclusive pelo owner enquanto habilitado; superuser/owner com controle de DDL não é impedido de modificar a política. O Claim Java expõe traceParent para a próxima etapa e omite-o do toString.

Linhas existentes e produtores sem contexto ficam NULL; não há backfill. Valor de fornecedor fora do formato é descartado para NULL pelo adapter, e SQL rejeita inserção inválida direta. Nenhum contexto é enviado ao RabbitMQ nesta entrega: headers AMQP/consumers serão 10B3c2/c3.

## Verificação

```powershell
.\mvnw.cmd -B -ntp -pl iam-core -am verify '-Dtest=OutboxTraceParentTest,OutboxPublisherTest,RabbitEventTransportTest,IamArchitectureTest,ArchitectureRulesTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=OutboxTraceContextIT,HttpServerTracingIT,OutboxPublicationIT,RuntimeDatabasePrivilegesIT' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djacoco.skip=true'
.\mvnw.cmd -B -ntp verify
```

OutboxTraceContextIT usa PostgreSQL real e contexto SDK: upgrade V22 → V23 com evento existente, auditoria/outbox revertidos juntos, NULL/invalid, restrições SQL, retry e nova instância de store com parent/payload preservados. Não reinicia JVM. HttpServerTracingIT registra via HTTP IAM real e confere parent do span servidor no banco, fora do JSON. Testes anteriores de publicação usam RabbitMQ real e preservam ACK/lease/retry/evento; não demonstram propagação de tracing AMQP. RuntimeDatabasePrivilegesIT recusa UPDATE(trace_parent) com login restrito. verify completo inclui gates existentes; smokes opt-in pelo nome continuam fora.

## Reversão e próximos passos

Para parar a captura, desativar tracing e reiniciar o IAM; metadados já persistidos permanecem. Não remover a coluna nem reescrever histórico Flyway para reverter o binário. Aplicação anterior usa INSERT de campos nomeados e deixa a coluna em NULL; avaliar compatibilidade da versão exata no processo de release. Retenção do parent acompanha a linha de outbox; nenhuma retenção nova foi criada.

Próximas propostas: **10B3c2** criar contexto de publicação a partir do valor persistido e injetar somente header AMQP permitido; **10B3c3** restaurar/limpar contexto no consumo IAM/enforcer, incluindo retries/redelivery e dados hostis. Exporter/backend e aceite de desempenho permanecem pendentes. [ADR-060](../adr/060-contexto-duravel-no-outbox.md), [evidências](../evidencias/etapa-10b3c1.md).

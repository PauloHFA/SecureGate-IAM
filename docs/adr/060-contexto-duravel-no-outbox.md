# ADR-060 — Contexto de tracing durável no outbox

- Estado: 10B3c1 implementada localmente; validação completa concluída; incluída neste commit; push pendente.
- Base: 32f3bfa; branch feat/outbox-trace-context.

## Divisão e decisão

Separar 10B3c em c1 (captura/persistência/claim), c2 (publicação AMQP) e c3 (consumidores IAM/enforcer). O publicador usa scheduler e retry durável: contexto de thread não sobrevive até a publicação. Persistir o parent na mesma transação do evento antes de instrumentar o broker. c2/c3 são propostas, pendentes.

V23 acrescenta trace_parent VARCHAR(55) nullable em iam_audit.outbox_events, com CHECK para W3C versão 00, IDs hex minúsculos não nulos e flags 00/01. Linhas antigas ficam NULL, sem contexto inventado/backfill. A função do trigger de imutabilidade passa a proteger também essa coluna; preserva a proteção dos campos anteriores. Não alterar V1–V22 nem checksums.

JdbcOutboxEvents recebe Supplier<String> opcional; o bootstrap consulta Tracer.currentSpan e injeta somente traceparent em um mapa local pelo Propagator restrito. Sem tracer/span, gravar NULL. Normalizar valor inválido para NULL antes do INSERT; o banco também rejeita valores fora do contrato. O fornecedor fica fora da camada de aplicação: domínio/eventos permanecem sem SDK. O INSERT do envelope e do parent continua dentro da mesma transação de negócio/auditoria, sem escrita posterior ou armazenamento por thread/sessão.

OutboxDelivery.Claim acrescenta traceParent; mantém construtor de seis parâmetros para compatibilidade de fontes. O toString continua omitindo payload/contexto de tracing. JdbcOutboxDelivery devolve a coluna a cada claim; retry, claim expirado, ACK e quarentena não a alteram. Nova instância do store recupera o mesmo parent do banco; não depender do tracer do publicador.

## Contrato e permissões

SecurityEvent continua schema v1/mesmo JSON e biblioteca sem dependência de tracing. Não adicionar traceparent ao payload ou à auditoria de negócio, nem confundir com correlationId. Baggage/tracestate/headers/tokens/PII não são capturados. IDs de trace são metadados não confiáveis, podem vir do cliente, não são segredo/autorização e não devem conter credenciais. Não usar como label Prometheus.

Os grants SELECT/INSERT de tabela do runtime já abrangem a coluna aditiva; os UPDATEs continuam restritos às colunas de entrega e não incluem trace_parent. Nenhum novo grant, login, policy Vault ou ACL RabbitMQ. O trigger também recusa alteração por owner mesmo com UPDATE amplo, enquanto estiver habilitado. Owner/superuser ainda controla schema e pode desabilitar triggers: não é fronteira contra administração do banco.

## Rollout e limites

Migrar para V23 com identidade de migração antes do novo binário, inclusive com tracing desligado: o SQL atual referencia a coluna. Em runtime sem Flyway, usar o procedimento separado existente. Binário anterior continua inserindo NULL pela ausência do campo no INSERT; não reverter schema/drop de coluna como rollback da aplicação. ALTER TABLE precisa de lock; planejar janela/monitoramento de lock conforme o ambiente. Não aplicada à stack persistente nesta entrega.

Testes PostgreSQL reais cobrem upgrade V22 com evento existente, rollback, NULL/valores inválidos, trigger e permissão runtime, re-claim/retry/ACK/claim antigo. Teste HTTP IAM real verifica captura do span servidor fora do payload. Reconstrução do store não equivale a reiniciar JVM ou broker. Não cria spans/headers AMQP, consumer scopes, backend ou tracing fim a fim: próximos c2/c3. [Operação](../runbooks/tracing-outbox-local.md), [evidências](../evidencias/etapa-10b3c1.md).

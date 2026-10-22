# Consumidor de auditoria local — 8B

## Preparação

Siga o [provisionamento 8A2](identidades-auditoria-local.md): infraestrutura saudável, Flyway V14 aplicada e `Provision-AuditDatabase.ps1` executado pelo administrador. Senhas de ingestão/manutenção ficam em `infra/.local`, ignoradas pelo Git. Não inicie consumidor sem essas credenciais.

Depois do build `./mvnw.cmd verify`, na raiz do projeto:

```powershell
# Consome mensagens já existentes. Não habilita o publicador.
.\infra\scripts\Start-IamCore.ps1 -EnableAuditConsumer -EnableAuditPartitions

# Para produção local de novos eventos e consumo no mesmo processo:
.\infra\scripts\Start-IamCore.ps1 -EnableBootstrap -EnableOutboxPublisher -EnableAuditConsumer -EnableAuditPartitions
```

Use as operações administrativas já documentadas em [provisionamento](provisionamento-local.md) para gerar eventos reais. O publicador envia o envelope para `security.events`; `audit.persist` recebe todos os tipos. Consumir não produz outra Outbox, evitando ciclo de eventos. Não purgue filas para diagnosticar backlog.

`-EnableAuditConsumer` implica `-EnableAuditIngestion`. `-EnableAuditPartitions` mantém meses futuros com a identidade de manutenção. A manutenção limitada não cria meses históricos arbitrários: um administrador deve avaliar e preparar partições necessárias para eventos antigos antes de reproduzir backlog.

## Configuração

| Variável | Padrão / requisito |
| --- | --- |
| `IAM_AUDIT_CONSUMER_ENABLED` | `false` |
| `IAM_AUDIT_INGESTION_ENABLED` | `false`; obrigatório para o consumidor |
| `IAM_AUDIT_CONSUMER_BATCH_SIZE` | `20`, permitido 1–100 |
| `IAM_AUDIT_CONSUMER_POLL_MILLIS` | `1000`, permitido 100–60000 |
| `IAM_RABBITMQ_HOST`, `IAM_RABBITMQ_PORT`, `IAM_RABBITMQ_VHOST` | host/porta/vhost da stack |
| `IAM_RABBITMQ_USERNAME`, `IAM_RABBITMQ_PASSWORD` | fornecidas pelo script sem imprimir os valores |
| `IAM_AUDIT_DATABASE_URL`, `IAM_AUDIT_INGEST_USERNAME`, `IAM_AUDIT_INGEST_PASSWORD_FILE` | conforme [8A2](identidades-auditoria-local.md) |

O script restaura as variáveis de processo ao encerrar. As duas variáveis de tamanho/intervalo podem ser definidas na sessão antes de iniciar; `.env` não é alterado. Todas as instâncias que consumirem a mesma fila devem ter o mesmo contrato e privilégios.

## Diagnóstico e recuperação

- Sucesso: `iam_audit.event_log` e recibos crescem uma vez por eventId; replay idêntico incrementa DUPLICATE sem criar outra linha.
- `iam.audit.consumer.messages`: APPENDED, DUPLICATE, INVALID, CONFLICT e RETRY. `iam.audit.consumer.batches` conta commits do caminho em lote; `iam.audit.consumer.errors` conta falhas de canal/topologia ou outras falhas externas ao store.
- INVALID/CONFLICT: consultar `audit.persist.dlq` no RabbitMQ Management local. Mensagens podem conter metadados de usuários: restrinja acesso e não publique corpos em logs/tickets. Investigue produtor/versão/ID antes de planejar replay administrativo; não reenvie um conflito alterando o ID apenas para contornar a deduplicação.
- RETRY: verificar disponibilidade, credenciais, grants, lock/statement timeouts e partições. Um lote que falha não confirma parcialmente; após corrigir a causa, o próximo tick tenta novamente. A falta de partição não leva a DLQ nem cria partição com credencial de ingestão.
- Broker indisponível: o worker reconecta em ticks seguintes. Mensagens sem ACK retornam à fila. Após commit e antes do ACK, o replay é esperado e deduplicado.
- Saúde HTTP/readiness continua baseada no IAM/banco principal: não é atestado de progresso do consumidor. Acompanhar métricas, mensagens ready/unacked, DLQ e contagem/atraso no banco.

Consulta administrativa somente leitura, usando uma conexão autorizada, sem expor credenciais em argumentos/logs:

```sql
SELECT event_type, count(*) FROM iam_audit.event_log GROUP BY event_type;
SELECT id, occurred_at, received_at FROM iam_audit.event_log
ORDER BY occurred_at DESC, id DESC LIMIT 20;
```

`Ctrl+C` encerra o processo e devolve mensagens ainda não confirmadas. O intervalo limita repetição por instância; não há fila de retry com TTL neste caminho nem limite de tentativas. As garantias de DLX são as da topologia local clássica, sem validação de alta disponibilidade. [ADR-030](../adr/030-consumidor-auditoria-em-lote.md) registra os demais limites.

A 8D1 acrescenta o resultado **LATE** para um evento novo em mês arquivado: isolamento e DLQ, preservando os vizinhos do lote. Replay idêntico arquivado continua DUPLICATE/ACK; conflito continua CONFLICT/DLQ. Após V16, reaplique o provisionamento de permissões antes de reiniciar a ingestão. Consulte o [guia de arquivamento](arquivamento-auditoria-local.md).

## Tracing opcional da ingestão

Desde a 10B3c3b, o perfil IAM http-tracing pode instrumentar persistência/disposição de lotes com raízes e links aos producers, mantendo transação e ACK após commit. Não habilita o consumer por conta própria nem altera permissões/schema. Sem tracer, comportamento anterior. Contextos inválidos não alteram validação de negócio. [Ativação, limites e reversão](tracing-consumidores-local.md), [ADR-063](../adr/063-tracing-auditoria-em-lote.md).

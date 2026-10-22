# ADR-063 — Tracing da auditoria em lote no IAM

- Estado: 10B3c3b implementada localmente; validação completa concluída (845 testes; ressalva de shutdown registrada); incluída neste commit; push pendente.
- Data: 21/10/2026.
- Base: 103553e; branch feat/outbox-trace-context.

## Contexto e decisão

RabbitAuditWorker mantém um único appendBatch transacional, seguido de ACKs individuais depois do commit. Um lote de até 100 mensagens pode conter producers com traceIds distintos e mensagens legadas. Não escolher a primeira mensagem como parent nem dividir a transação para produzir spans individuais.

Adicionar AuditConsumerTrace somente na infraestrutura, com Tracer opcional conectado em AuditConsumerConfiguration. Reutilizar o provider/sampler IAM do perfil http-tracing; nenhuma nova dependência, flag, migração ou exporter. Construtor anterior preserva comportamento sem tracing e nem inspeciona headers de contexto.

Extrair somente traceparent String ou LongString RabbitMQ com 55 caracteres; validar W3C versão 00, IDs hex minúsculos não nulos e flags 00/01 pelo OutboxTraceParent existente. Ausência, tipo/formato inválido ou variantes de chave ambíguas viram contexto ausente, sem alterar a validação de negócio. Não capturar mapas/baggage/tracestate/B3 nem usar correlationId/MDC como parent. Delivery retém apenas parent validado e redelivery e omite o parent do toString.

Após coleta/validação dos envelopes, criar **audit.consume.batch CONSUMER como raiz explícita**. Adicionar links sem atributos aos contextos de produtores distintos (deduplicados por traceId/spanId, até o limite de 100 mensagens). Mensagem sem contexto não produz link nem contexto inventado. O traceId do lote difere dos producers; essa é uma relação por links, não filiação única. Sampler local usa a nova raiz, independente dos flags dos links. Decode puro anterior ao lote não recebe span próprio; o span cobre persistência, fallback e disposição.

Rejeição de envelope inválido usa audit.consume.message com phase=invalid, parent explícito ou raiz, cobrindo a disposição após falha do decoder. Após rollback de conflito/mês arquivado, cada tentativa isolada usa audit.consume.message com phase=isolated e parent da respectiva mensagem ou raiz. O escopo isolado restaura o lote ao terminar. Não criar spans de processamento para mensagens restantes apenas reencaminhadas após uma falha; elas já pertencem aos links do lote.

Abrir escopo na chamada síncrona e preencher/restaurar apenas traceId/spanId no MDC em finally, preservando valores anteriores. Encerrar span em sucesso/exceção, sem registrar mensagem/stack livre. Cinco atributos finitos: messaging.system=rabbitmq; messaging.consumer=audit; messaging.phase=batch/isolated/invalid; messaging.redelivered=true/false (qualquer redelivery no lote); messaging.outcome=stored/isolated/retry/invalid/appended/duplicate/conflict/late/unavailable. Sem payload, identidade, UUID como atributo ou métrica nova. IDs de links são metadados não confiáveis, não autorização.

## Garantias e limites

Preservar appendBatch único, commit antes do ACK, resultado validado, isolamento de poison/late, retry do restante, desconexão em ACK incerto e deduplicação. Batch failed seguido de fallback mantém um span raiz abrangendo tentativas isoladas, sem fingir que todas as mensagens têm o mesmo parent. Retry/redelivery cria nova raiz com links originais; não persistir metadados de consumo no banco/envelope v1.

A etapa conclui a instrumentação de consumo prevista na 10B3c3, junto com enforcer c3a. Não conclui o aceite global 10B: backend/exportação e coleta conjunta sustentada continuam pendentes, organizados na proposta **10B4**, antes da medição 10C. Não afirmar um teste novo único HTTP → outbox → ambas as filas → banco/Redis; há evidências por trecho e regressão funcional ponta a ponta.

Nenhuma operação sobre a stack persistente/.env; sem nova migração (V23 continua pré-requisito do IAM anterior). Desativar tracing/reiniciar suprime spans sem mudar semântica de ingestão. [Operação](../runbooks/tracing-consumidores-local.md), [evidências](../evidencias/etapa-10b3c3b.md).

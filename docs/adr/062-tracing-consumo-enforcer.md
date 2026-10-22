# ADR-062 — Tracing do consumo no enforcer

- Estado: 10B3c3a implementada localmente; validação completa concluída (835 testes); incluída neste commit; push pendente.
- Data: 21/10/2026.
- Base: be06e27; branch feat/outbox-trace-context.

## Divisão e decisão

Dividir a proposta 10B3c3 em **10B3c3a (enforcer)** e **10B3c3b (auditoria IAM em lote)**. O enforcer processa/ACK uma mensagem por vez; RabbitAuditWorker grava vários eventos atomicamente e pode receber parents distintos no mesmo lote. Não escolher um parent arbitrário nem desfazer batching/atomicidade para instrumentá-lo; a modelagem desse lote permanece na c3b.

Adicionar bridge OTel/Micrometer somente ao módulo enforcer. Provider explícito sob management.tracing.enabled=true, perfil consumer-tracing, resource service.name=policy-enforcer, sampler local traceIdRatioBased (padrão 0,1). Excluir autoconfigurações genéricas OTel/tracing/logging para evitar instrumentos/exporters automáticos. Sem tracer/provider no padrão, sem novo listener HTTP, propagador global, exporter ou captura Redis automática. Consumidores continuam opt-in independente em enforcer.consumer.enabled.

ConsumerTrace envolve processamento e disposição da entrega nas duas filas fixas. Com tracer, ler apenas traceparent versão 00: String ou LongString RabbitMQ com 55 caracteres, IDs hex minúsculos não nulos, flags 00/01. Não converter objetos arbitrários; ausência, formato inválido ou variantes de chave ambíguas criam raiz explícita. Metadado inválido não muda a validação de negócio nem transforma mensagem válida em poison. Mapas externos/baggage/tracestate/B3 não se tornam contexto, atributos ou permissões.

Criar security.consume CONSUMER por entrega, com parent explícito ou raiz; não herdar worker/correlationId/MDC. Sampler local não obedece automaticamente ao sampled remoto. Abrir escopo para decoder/handler/efeito Redis e ACK/REJECT/NACK; fechar em finally e preservar valores anteriores de traceId/spanId do MDC. O MDC é preenchido explicitamente nesse escopo para não depender de um wrapper global OTel no executor. Nenhum parent é guardado no worker entre entregas.

Quatro atributos finitos: messaging.system=rabbitmq, messaging.consumer=revocation/block_policy, messaging.redelivered=true/false e messaging.outcome=ack/reject/retry/unavailable. Exceção ao processar ou dispor entrega registra unavailable, sem texto/evento de exceção. Preservar catch externo, desconexão e requeue de mensagens não confirmadas. Retry/redelivery gera novo filho do mesmo parent recebido. Não mudar payload v1, ACK após efeito, deduplicação, TTL ou métricas existentes.

JSON estruturado do enforcer passa de cinco a sete campos, acrescentando traceId/spanId validados (vazios fora de contexto); argumentos, mensagem livre, exceção e MDC arbitrário continuam excluídos. IDs são metadados não confiáveis, não prova de identidade nem labels de métricas.

## Consequências e limites

Sem nova migração/topologia/credencial. V23 continua sendo pré-requisito do binário IAM da etapa anterior; nenhuma operação sobre stack persistente foi executada. Desativar consumer-tracing/management.tracing.enabled e reiniciar enforcer interrompe spans; os headers continuam sendo ignorados funcionalmente pelo decoder legado. O formato JSON permanece com sete campos, mesmo sem tracing.

A 10B3c3b ainda deve resolver auditoria em lote, escopos e links apropriados, sem comprometer a transação/isolamento de poison/retry/ACK. A 10B3c3 completa, exporter/backend, retenção e medição de desempenho permanecem pendentes.

[Operação](../runbooks/tracing-consumidores-local.md), [evidências](../evidencias/etapa-10b3c3a.md).

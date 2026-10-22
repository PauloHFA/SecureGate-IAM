# ADR-061 — Tracing da publicação AMQP

- Estado: 10B3c2 implementada localmente; validação completa concluída (824 testes); incluída neste commit; push pendente.
- Data: 21/10/2026.
- Base: 57f34bf (10B3c1), branch feat/outbox-trace-context.

## Contexto e decisão

O worker publica depois da transação HTTP. O parent durável da V23, e não o span eventualmente corrente no worker, deve determinar a linhagem de cada tentativa. Instrumentar RabbitEventTransport, sem dependências de tracing na aplicação/domínio e sem alterar SecurityEvent v1, OutboxPublisher ou o contrato EventTransport.

Receber Tracer opcional no bootstrap. Sem tracer, manter construtor anterior e headers contendo apenas schemaVersion; inclusive eventos com parent persistido não propagam tracing. O perfil http-tracing existente habilita o tracer; iam.outbox.publisher.enabled continua sendo a opção independente de execução do worker. Não adicionar nova flag/exporter.

Com tracer, iniciar outbox.publish de tipo PRODUCER para cada chamada. Validar novamente traceParent com OutboxTraceParent, construir parent explícito somente com traceId/spanId/sampled e descartar qualquer outro metadado. Na ausência de parent válido, usar raiz explícita. Assim, o contexto corrente do scheduler nunca substitui o parent durável. O sampler local continua independente do bit sampled persistido.

Serializar estritamente o contexto do span produtor em W3C versão 00/flags 00 ou 01, validá-lo e adicionar somente traceparent ao mapa novo de headers. Não copiar mapas externos, baggage, tracestate ou B3, nem usar correlationId/MDC como parent. A serialização direta desses três campos evita executar injetores genéricos com campos adicionais. schemaVersion continua presente; body UTF-8 e propriedades de identidade/correlação/persistência permanecem os mesmos.

Fechar o escopo e encerrar span em finally. Registrar somente messaging.system=rabbitmq e messaging.outcome no catálogo acked/unroutable/nack/confirm_timeout/broker_unavailable/invalid_event; sem evento de exceção, texto de erro, payload, UUID, tipo arbitrário de evento, credenciais ou endereço do broker. INVALID_EVENT também encerra um span, sem publicar. ACK significa confirmação do broker sem retorno mandatory, não conclusão de consumidor nem persistência de published_at.

## Consequências e limites

Cada retry é novo filho do mesmo parent persistido, com spanId novo. O header carrega o filho produtor, enquanto a coluna trace_parent continua sendo o parent original e imutável. Falha após confirm e antes do UPDATE pode redeliver o mesmo evento; sem mudança da garantia at-least-once, lease, backoff ou deduplicação dos consumidores.

Não há nova migração. V23 continua obrigatória antes do binário, inclusive com tracing desligado. Não executar migração/alteração da stack persistente nesta entrega. Desativar tracing suprime spans/headers nas próximas publicações, preservando os dados já gravados. Consumidores IAM/enforcer continuam sem extrair esse contexto até a 10B3c3. Sem exporter/backend; não afirmar rastreamento distribuído completo ou aceite de desempenho.

[Operação](../runbooks/tracing-outbox-local.md), [evidências](../evidencias/etapa-10b3c2.md), [decisão de persistência](060-contexto-duravel-no-outbox.md).

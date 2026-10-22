# Tracing dos consumidores — 10B3c3a/c3b

## Preparação e ativação

1. Usar JDK 21/Wrapper e a configuração RabbitMQ/Redis do enforcer já documentada em [infra](../../infra/README.md). Não alterar contas, ACLs ou topologia para tracing.
2. Manter enforcer.consumer.enabled=true e credenciais habituais no mecanismo de configuração do ambiente; não imprimir senha em comandos/logs. Consumer-tracing sozinho não habilita workers.
3. Habilitar o perfil **consumer-tracing** no enforcer. Para logs JSON, combinar com **structured-logs**. O IAM continua usando seu perfil http-tracing; o perfil novo pertence ao módulo enforcer.
4. ENFORCER_TRACING_SAMPLING_PROBABILITY configura amostragem local entre 0 e 1 (padrão 0,1); valor fora do intervalo impede startup. Não é quota nem mecanismo de autorização. management.tracing.enabled é falso por padrão e pode ser sobrescrito explicitamente.
5. Sem perfil/flag, não há provider/tracer; o consumer conserva processamento e disposição anteriores. Não instalar exporter/collector nesta etapa: spans terminados não são armazenados em produção.

Exemplo de opções de perfis para o executável enforcer, mantendo as demais opções de execução do ambiente:

```text
--spring.profiles.active=structured-logs,consumer-tracing
```

## Contexto e disposição

As filas enforcer.revocation e enforcer.block-policy usam security.consume CONSUMER por entrega. Header traceparent do produtor determina parent; legacy/NULL/valor inválido gera raiz independente. Não herdar contexto do scheduler ou do evento anterior. Aceitar somente String/LongString W3C 00 de 55 caracteres, IDs minúsculos não nulos, flags 00/01; ignorar baggage/tracestate/B3. Chaves ambíguas são descartadas como parent. Isso não altera ACK/rejeição de negócio do envelope.

O span cobre decoder, handler, efeito Redis e disposição no canal. ACK segue após efeito; RETRY usa NACK requeue e encerra o batch como antes; REJECT segue para DLQ. Falha de canal mantém desconexão/requeue de unacked. Resultado unavailable indica exceção durante esse processamento/disposição; não inclui texto livre. Retry/redelivery tem span novo, com parent original preservado no header pelo broker.

Atributos limitados a messaging.system, messaging.consumer, messaging.redelivered e messaging.outcome. JSON do enforcer tem sete campos fixos, com traceId/spanId vazios fora do escopo. Escopo e MDC são restaurados em finally. Não registrar headers/body/segredos ao diagnosticar; traceId não é identidade confiável nem label de métrica.

## Verificação

```powershell
.\mvnw.cmd -B -ntp -pl policy-enforcer -am spotless:apply '-Dtest=ConsumerTraceTest,ConsumerTracingConfigurationTest,SafeOperationalLogFormatterTest,StructuredLoggingProfileTest,EnforcerArchitectureTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=RevocationConsumerIT,MetricsDisabledIT,MetricsEndpointIT,VaultKvBootstrapIT' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djacoco.skip=true' verify
.\mvnw.cmd -B -ntp verify
```

ConsumerTraceTest usa SDK real com ações simuladas: String/LongString, contexto hostil, IDs zero, chaves ambíguas, sampler zero, outcomes e restauração de MDC/contexto em exceções. RevocationConsumerIT usa RabbitMQ/Redis reais para retry/recovery e mensagem poison seguida de legacy no mesmo worker; a falha inicial do store no novo teste é injetada, não queda real do Redis. Testes anteriores continuam cobrindo indisponibilidade real Redis/deduplicação/TTL. Contexto Spring padrão sem tracer/worker e contexto opt-in com tracer/duas filas são verificados. Não equivale a encadear HTTP IAM → outbox → broker → enforcer em um único novo teste.

## Auditoria IAM em lote — 10B3c3b

1. Manter preparação/credenciais de [ingestão dedicada](consumidor-auditoria-local.md) conforme os guias existentes. O tracer não concede nem modifica permissões de banco ou RabbitMQ.
2. Habilitar o perfil IAM **http-tracing** e a configuração habitual iam.audit.consumer.enabled=true, com o store de ingestão dedicado disponível. Tracing sozinho não inicia consumidor; sem tracer, comportamento anterior.
3. O worker lê/valida envelopes de até 100 mensagens; após isso inicia audit.consume.batch CONSUMER, raiz explícita com links de produtores distintos. Não há parent único escolhido do lote. Headers ausentes/inválidos não criam links e não rejeitam mensagens de negócio válidas.
4. O span do lote cobre appendBatch, fallback e ACK/NACK/REJECT; decode puro acontece antes do span. Um único appendBatch é mantido e ACK segue depois do commit.
5. Rejeição de envelope inválido tem span audit.consume.message phase=invalid cobrindo disposição. Fallback após rollback tem span por tentativa isolada phase=isolated e parent da mensagem, restaurando o lote ao terminar. Restantes apenas reenfileiradas não geram spans individuais.
6. Cinco atributos finitos: messaging.system, messaging.consumer, messaging.phase, messaging.redelivered e messaging.outcome. Lote marca redelivery se qualquer mensagem foi redelivered. Links têm apenas contexto validado, sem atributos arbitrários.
7. Retry/redelivery cria nova raiz de lote, com links dos mesmos produtores. ACK incerto desconecta/requeue como antes; deduplicação não muda. Nenhum contexto de consumo é gravado no payload/banco.
8. Logs estruturados IAM continuam com sete campos. IDs/MDC são restaurados em finally, inclusive quando banco/canal falham; não registrar headers/payload/credenciais para diagnóstico.

```powershell
.\mvnw.cmd -B -ntp -pl iam-core -am verify '-Dtest=AuditConsumerTraceTest,RabbitAuditWorkerTest,AuditConsumerConfigurationTest,IamArchitectureTest,ArchitectureRulesTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=AuditIngestionIT,OutboxPublicationIT' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djacoco.skip=true'
.\mvnw.cmd -B -ntp verify
```

AuditIngestionIT usa banco/broker reais e store restrito; teste novo mistura parents distintos e legacy na mesma transação, e outro testa retry/recovery com falha inicial injetada. SDK local captura spans/links; não há backend de produção. [ADR-063](../adr/063-tracing-auditoria-em-lote.md), [evidências 10B3c3b](../evidencias/etapa-10b3c3b.md).

## Reversão e pendências

Desativar tracing e reiniciar enforcer mantém comportamento de negócio e formato JSON de sete campos; não exige migração. Nenhum dado de negócio foi alterado nesta entrega fora de ambientes descartáveis de testes. A 10B3c3b instrumenta audit.persist em lote com links e preserva commit/isolamento/ACK. Não remover V23 para reverter; nenhum schema novo nesta etapa. **10B4** propõe fechar backend/exportação e coleta conjunta antes de 10C, mantendo aberto o aceite global 10B. Publicação, CI, exporter/backend, coleta sustentada e medição ainda têm aceite próprio.

[ADR-062](../adr/062-tracing-consumo-enforcer.md), [evidências](../evidencias/etapa-10b3c3a.md), [publicação outbox](tracing-outbox-local.md).

## Backend e próximos passos

A [10B4a](backend-tracing-local.md) acrescenta Tempo/Grafana local opcional, validado com dados sintéticos. Ela não muda os perfis de instrumentação nem envia spans destes consumidores. A exportação OTLP dos serviços será 10B4b; a validação integrada/sustentada será 10B4c.

## Continuação 10B4b

Exportação OTLP opcional implementada no IAM/enforcer, sem alterar a stack persistente. Ativação, destino local, fila/timeouts, descarte em falha e reversão: [guia de exportação](../runbooks/exportacao-otlp-local.md). A validação integrada/sustentada continua na 10B4c; as limitações de entrega anteriores acima são históricas.

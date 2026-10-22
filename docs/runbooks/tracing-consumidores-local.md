# Tracing dos consumidores — 10B3c3a

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

## Reversão e pendências

Desativar tracing e reiniciar enforcer mantém comportamento de negócio e formato JSON de sete campos; não exige migração. Nenhum dado de negócio foi alterado nesta entrega fora de ambientes descartáveis de testes. IAM audit.persist ainda não extrai tracing: **10B3c3b** deve modelar lotes com parents diferentes preservando commit/isolamento/ACK. Publicação, CI, exporter/backend, coleta sustentada e medição ainda têm aceite próprio.

[ADR-062](../adr/062-tracing-consumo-enforcer.md), [evidências](../evidencias/etapa-10b3c3a.md), [publicação outbox](tracing-outbox-local.md).

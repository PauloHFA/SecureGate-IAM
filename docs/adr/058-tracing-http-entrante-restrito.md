# ADR-058 — Tracing HTTP entrante restrito

- Estado: implementação local da 10B3b1; publicação/CI pendentes.
- Base: b6e2363; branch feat/http-server-tracing.

## Divisão

Os clientes Vault usam java.net.http.HttpClient diretamente, sem instrumentação automática Spring. Dividir a 10B3b em 10B3b1 (entrada servlet) e 10B3b2 (clientes de saída) evita declarar cobertura inexistente. 10B3b2 e 10B3c (outbox/AMQP) continuam propostas e pendentes.

## Decisão

Adicionar micrometer-tracing-bridge-otel com versão do BOM Boot 3.5.16 (Micrometer Tracing 1.5.12/OpenTelemetry 1.49.0 resolvidos localmente). Perfil http-tracing habilita management.tracing.enabled; padrão false. Configuração explícita cria SdkTracerProvider, OtelTracer, propagator e handler somente quando habilitado. Excluir autoconfigurações globais OpenTelemetry, logging OTel, MicrometerTracing e NoopTracer para impedir instrumentação e pipelines adicionais fora desse contrato. Usar provider local com Resource service.name=iam-core e encerramento close; sem exporter, fila de exportação, agente ou backend.

Usar o filtro de observação servlet existente. Handler herda a gestão de escopo Micrometer, mas restringe os pontos que criam/concluem spans e descartam eventos/Throwable. Nome único http.server; apenas http.method (oito métodos conhecidos ou OTHER), http.status_class (1xx–5xx/unknown) e http.failure (true/false). Não copiar atributos da Observation, URL/rota/query, headers, endereço remoto, identidade, payload, mensagem de erro ou stack trace. Erro é flag, não descrição/exception event do SDK. Métricas mantêm suas convenções existentes.

Registrar DefaultMeterObservationHandler também quando tracing está ligado: o handler TracingAware padrão exige TracingContext em todas as observações, inclusive internas de segurança que intencionalmente não criam spans aqui. Preservar a opção long-task timer. A composição evita HTTP 500 por contexto ausente e preserva labels regulares. A autoconfiguração Prometheus pode associar contexto amostrado como exemplares OpenMetrics; isso é distinto de labels e não foi validado por scrape nesta entrega.

Propagação aceita somente traceparent W3C versão 00: minúsculas, formato exato de 55 caracteres, IDs não nulos validados pelo SDK. Rejeitar headers servlet duplicados, versões futuras, vírgulas, whitespace e formatos inválidos; gerar nova raiz nesses casos. Não extrair nem injetar baggage/tracestate/B3. Aceitar contexto remoto não concede autoridade. IDs de trace são metadados não confiáveis, não credenciais; não colocar segredos neles.

Amostragem local traceIdRatioBased, padrão 0,1; IAM_HTTP_TRACE_PROBABILITY aceita [0,1]. Não usar parentBased: sampled remoto não supera probabilidade local zero, e probabilidade local um pode registrar parent remoto unsampled. A decisão é determinística pelo trace ID; não é limitador de taxa ou proteção contra cliente que escolhe IDs.

O JSON operacional IAM passa a sete campos, acrescentando traceId/spanId hexadecimais não nulos com 32/16 caracteres ou vazio. Enforcer mantém cinco campos. correlationHash/header anterior permanecem independentes. IDs podem existir em spans não amostrados. Sem perfil structured-logs, aplica-se a configuração anterior de logging, sem garantia do schema sanitizado.

## Limites

Somente contexto HTTP servlet entrante; clientes JDK/Vault, banco, Redis, AMQP, futures, schedulers e spans manuais não instrumentados. Sem promessa de tracing fim a fim, persistência, backend ou observabilidade completa. Esta entrega não cria endpoint de traces nem devolve trace ID no header de resposta. Processors/adapters externos e configurações futuras precisam de revisão própria; não adicionar exporter antes de avaliar destino/TLS/retensão/autorização. Cabeçalhos ainda estão sujeitos a limites gerais do servidor/proxy, antes do parser.

## Referências

[Operação](../runbooks/tracing-http-local.md), [evidências](../evidencias/etapa-10b3b1.md). Contratos: [Boot 3.5 tracing](https://docs.spring.io/spring-boot/3.5/reference/actuator/tracing.html), [instrumentação Micrometer](https://docs.micrometer.io/micrometer/reference/observation/instrumenting.html), fontes do Boot 3.5.16 no sources.jar e APIs compiladas da versão resolvida. A configuração explícita limita a abrangência da autoconfiguração genérica.

# ADR-065 — Exportação OTLP local e opcional

- Estado: implementada; validação registrada nas evidências 10B4b.
- Data: 21/10/2026.
- Complementa: [ADR-064](064-backend-local-de-tracing.md).

## Contexto e decisão

O backend 10B4a recebe OTLP, mas os providers explícitos do IAM e enforcer ainda não enviavam spans. Acrescentar configuração de bootstrap em cada executável, sem dependência de observabilidade em domínio/aplicação ou no contrato security-events. Reutilizar ObjectProvider de SpanProcessor dos providers existentes e as políticas atuais de amostragem/propagação.

Exportação exige securegate.tracing.export.enabled=true e management.tracing.enabled=true; sem opt-in não há exporter/fila/thread de exportação. Ativar exportação sem tracing falha no startup. Endpoint padrão http://127.0.0.1:14318/v1/traces; aceitar somente esse IP literal, HTTP, porta explícita 1024–65535 e path exato, sem userinfo/query/fragment. Não aceitar hostname/DNS, cabeçalhos de autenticação ou destinos remotos nesta entrega.

Dependências opentelemetry-exporter-otlp e opentelemetry-exporter-sender-jdk nas versões do BOM Spring Boot (1.49.0 nesta base). Excluir sender-okhttp: seu cliente segue redirecionamentos por padrão. O transporte JDK usa HttpClient sem seguir redirects; teste com 307 confirma ausência de segundo envio. Sem SDK global, autoinstrumentação ou configuração genérica OTEL_*; os providers explícitos continuam responsáveis pelos spans.

## Limites e ciclo de vida

BatchSpanProcessor: fila de 512 spans e lote de até 128, intervalo de 1 s. Exporter: conexão de até 500 ms e requisição de até 1 s; espera do processor pelo exporter de até 2 s por lote. Retry OTLP desativado. Limites fixos no código para manter o ensaio local reproduzível; amostragem permanece configurável nos perfis existentes.

Encerrar um span apenas tenta enfileirar; fila cheia descarta telemetria. Falha/timeout descarta o lote, sem persistência/reenvio. Novos lotes voltam a ser enviados quando o backend recupera. Isso não muda transações, autenticação, autorização, publicação, ACK/NACK ou retry de mensagens. Tracing é best effort e não substitui a auditoria durável. A fila limita número de spans, não bytes nem volume de traces retidos no Tempo.

O provider é dono dos processors e fecha primeiro; close do bean é fallback idempotente para falha parcial de startup. O fechamento tenta drenar lotes, portanto não é instantâneo. Os limites de 1/2 s são por requisição/lote, não SLA de encerramento de toda a aplicação; o timeout global existente do SDK/provider não foi ampliado. A ressalva histórica de shutdown Hikari é independente.

## Correção de encerramento encontrada na validação

O verify ficou bloqueado em VaultKvBootstrapIT do enforcer. Thread dump mostrou main aguardando o monitor de RabbitRevocationWorker.close, retido pelo tick dentro de Connection.abort() sem timeout após interrupção de uma escrita NIO. Corrigir disconnect para abort(1000): após 1 s o cliente força fechamento do socket. Não aumentar timeout de teste nem mudar ACK/NACK/handler; entregas sem ACK continuam recuperáveis pelo broker.

Regressão com RabbitMQ real/NIO e interrupção antes do ACK falhou no código anterior por não concluir em 5 s. A correção deve permitir fechamento e projeção da mensagem por outro worker. Esse bloqueio é distinto da ressalva histórica de shutdown do fork IAM/Hikari, que permanece aberta. [Contrato oficial abort(int)](https://github.com/rabbitmq/rabbitmq-java-client/blob/v5.25.0/src/main/java/com/rabbitmq/client/Connection.java), consultado também via skill firecrawl-developer-index.

## Dados e consequências

Exportar apenas a instrumentação explícita já revisada: http.server, vault.http, outbox.publish, audit.consume.batch, audit.consume.message e security.consume. Nomes/atributos finitos permanecem no [inventário](../OBSERVABILIDADE.md); recursos apenas service.name=iam-core/policy-enforcer. IDs, parents e links permitem consulta; não são identidade/autorização. Não acrescentar body, URL, headers, email, token, erro livre, baggage, eventos de exceção ou labels com identificadores.

O exporter não é um sanitizador de spans arbitrários: novas instrumentações precisam de revisão dos dados antes de ativação. Sem agente Java nem exportação automática de logs/métricas. O destino local é confiável e não autenticado; não constitui solução de egress/TLS para produção. Apps em containers e coleta remota exigem decisão posterior, sem relaxar esta validação.

Alternativas adiadas: Collector central, exportação externa autenticada e novo módulo compartilhado. Duas configurações pequenas de bootstrap evitam acoplar bibliotecas de domínio/eventos ao SDK. Contrato idêntico é exercitado em ambos os módulos.

## Verificação e fontes

[LocalOtlpExportConfigurationTest IAM](../../iam-core/src/test/java/com/securegate/iam/bootstrap/LocalOtlpExportConfigurationTest.java) e [enforcer](../../policy-enforcer/src/test/java/com/securegate/enforcer/bootstrap/LocalOtlpExportConfigurationTest.java): provider Spring real e receptor HTTP descartável, protobuf com recurso/parent/link, default off, configuração inválida, sampler zero, saturação, 503 sem retry, recuperação, timeout, fechamento e redirect recusado. Não comprovam cadeia completa de negócio consultável no Tempo ou coleta sustentada: 10B4c.

Fontes primárias da versão consultadas via skill firecrawl-developer-index e código oficial: [OTLP HTTP builder](https://github.com/open-telemetry/opentelemetry-java/blob/v1.49.0/exporters/otlp/all/src/main/java/io/opentelemetry/exporter/otlp/http/trace/OtlpHttpSpanExporterBuilder.java), [batch processor](https://github.com/open-telemetry/opentelemetry-java/blob/v1.49.0/sdk/trace/src/main/java/io/opentelemetry/sdk/trace/export/BatchSpanProcessor.java), [transporte JDK](https://github.com/open-telemetry/opentelemetry-java/blob/v1.49.0/exporters/sender/jdk/src/main/java/io/opentelemetry/exporter/sender/jdk/internal/JdkHttpSender.java).

[Operação](../runbooks/exportacao-otlp-local.md), [evidências](../evidencias/etapa-10b4b.md).

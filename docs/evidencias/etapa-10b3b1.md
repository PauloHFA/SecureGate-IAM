# Evidências — 10B3b1 tracing HTTP entrante

- Data: 21/10/2026, UTC−03.
- Base: b6e2363; branch feat/http-server-tracing.
- Estado: implementação e validação locais concluídas; incluída neste commit; push/CI remota pendentes.

## Execução

Perfil opcional http-tracing, SDK/bridge Micrometer gerenciados pelo BOM, contexto W3C restrito, sampler local, handler servlet com três atributos finitos e campos traceId/spanId no JSON IAM. Sem exporter/backend, sem mudança de schema, credenciais ou stack persistente. Divisão: 10B3b1 entrada; 10B3b2 clientes JDK; 10B3c outbox/AMQP. Últimas duas propostas, pendentes.

A primeira execução encontrou autoconfiguração de logging OTel ativada pela dependência, sem Resource depois da exclusão do pipeline genérico. Excluída explicitamente: a entrega configura somente o provider/handler desejados. O teste real seguinte detectou TracingAwareMeterObservationHandler exigindo TracingContext para observações internas Spring Security que não criam spans neste contrato, causando HTTP 500. Substituído por DefaultMeterObservationHandler, com opção long-task preservada. Diagnóstico temporário executado sem encoder JSON no processo de teste para identificar a causa; perfis finais mantêm JSON restrito. A correlação MDC também exigiu registrar OtelTracer.EventPublisher como bean: o listener de contexto Boot usa esse contrato para sincronizar/restaurar os IDs. Após o ajuste, asserções MDC passaram. Sem dados reais nos fixtures.

## Validação

Seleção concluída:

```powershell
.\mvnw.cmd -B -ntp -pl iam-core -am spotless:apply '-Dtest=HttpTracingConfigurationTest,RestrictedHttpTracePropagatorTest,SafeOperationalLogFormatterTest,StructuredLoggingProfileTest,CorrelationIdFilterTest,IamArchitectureTest,ArchitectureRulesTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=HttpServerTracingIT,PrometheusIT' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djacoco.skip=true' verify
```

BUILD SUCCESS às **15:10:20 UTC−03**, **36,235 s**. **22 testes unitários/arquitetura + 6 integração**, zero falhas/erros/skips. HttpServerTracingIT (3) usa IAM/servlet/segurança/PostgreSQL reais e processor em memória. PrometheusIT (3) verifica runtime sem tracer/provider por padrão, proteção de métricas e convenções/histogramas preservados. A seleção pula JaCoCo; não comprova o gate completo de cobertura.

Verificações de tracing: trace ID remoto preservado, span servidor filho, IDs no MDC, duplicados descartados, requisição seguinte com nova raiz, status 500/403, sem eventos/links/tracestate nem valores sintéticos de URL/query/headers/erro nos dados concluídos. Sampler zero recusa registro mesmo com parent sampled; sampler um registra parent unsampled. Parser testa tamanho, versão, uppercase, zeros e duplicidade. JSON testa limites/não nulos/hexadecimal e descarte de conteúdo livre.

Build completo `.\mvnw.cmd -B -ntp verify`: **BUILD SUCCESS às 15:21:46 UTC−03**, **10 min 29 s**, exit code nativo **0**. **804 testes**, zero falhas/erros/skips: security-events 43; IAM 394 unitários + 290 integração; enforcer 44 unitários + 22 integração; fim a fim 11. Dois gates JaCoCo atendidos; arquitetura incluída. A seleção anterior se sobrepõe ao build completo e não deve ser somada. Smokes opcionais de observabilidade pelo nome não foram repetidos.

**Ressalva de encerramento:** após os 290 testes de integração IAM aprovados, Surefire registrou `The exit has elapsed 30 seconds after System.exit(0)` e encerrou o fork. O dump local `iam-core/target/failsafe-reports/2026-10-21T15-11-19_095-jvmRun1.dump` mostra SpringApplicationShutdownHook aguardando HikariPool.shutdown/awaitTermination. O mesmo ponto de espera existe no dump anterior `2026-10-21T00-55-28_614-jvmRun1.dump`, criado às 01:02:09, antes desta entrega. Não atribuir causalidade ao tracing nem declarar esse problema resolvido: esta revisão confirmou a recorrência, sem corrigir o lifecycle dos contextos/pools de teste. Nenhum teste falhou, e o reactor terminou com exit 0, mas o encerramento do fork não foi limpo. Não aumentou timeout nem suprimiu o aviso. Manter como pendência de diagnóstico de testes/lifecycle.

Verificações documentais: **1.252 links locais** em README/docs sem destino ausente; **22 arquivos alterados/novos** UTF-8 sem BOM e sem whitespace final; git diff --check aprovado. Logs ignorados locais: .firecrawl/etapa10b3b1-targeted.log e .firecrawl/etapa10b3b1-verify.log. Fontes Java não mudaram após a seleção aprovada.

Exemplares OpenMetrics não foram validados por scrape. O Boot pode associar IDs via PrometheusExemplarsAutoConfiguration quando o contexto é amostrado; isso não equivale a adicionar IDs como labels regulares. A revisão documental registra esse limite sem afirmar uma ausência de exemplares não testada. [ADR-058](../adr/058-tracing-http-entrante-restrito.md), [operação](../runbooks/tracing-http-local.md).

## Pendências

Sem tracing de clientes JDK/Vault, contexto durável assíncrono, collector/backend, publicação/CI, ensaios de capacidade ou aceite global de observabilidade. Sem teste de continuidade async servlet/futures. O schema finito cobre spans do handler HTTP, não extensões/processors/agentes adicionados externamente.

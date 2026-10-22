# Evidências — 10B3a logs operacionais JSON restritos

- Data: 21/10/2026 (UTC−03).
- Base: 771487a; branch feat/structured-operational-logs.
- Estado: implementação local; incluída neste commit; push/CI remota pendentes.

## Escopo executado

Formatadores/schema fechado nos bootstraps IAM/enforcer, perfil opcional structured-logs console/arquivo, catálogo finito e SHA-256 de correlação limitada. Sem captura de requisição, spans, contexto AMQP, collector ou backend. Sem alteração da stack persistente, .env, schema V22, credenciais ou protocolos. [ADR-057](../adr/057-logs-operacionais-json-restritos.md), [operação](../runbooks/logs-estruturados-local.md).

## Testes direcionados

```powershell
.\mvnw.cmd -B -ntp -pl iam-core,policy-enforcer -am spotless:apply '-Dtest=SafeOperationalLogFormatterTest,StructuredLoggingProfileTest,CorrelationIdFilterTest,IamArchitectureTest,ArchitectureRulesTest,EnforcerArchitectureTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Djacoco.skip=true' test
```

BUILD SUCCESS às 14:40:25 UTC−03; duração 22,969 s. Os testes selecionados de formato/perfil/correlação/arquitetura passaram. Não é teste de startup completo dos serviços: contexto mínimo sem autoconfiguração de banco/broker. O teste de perfil usa encoders reais de console/arquivo e verifica JSON e ausência de marcadores sintéticos de segredo. Asserções de contrato verificam hash conhecido, limites, linha única e descarte de MDC/pares/throwable/argumentos.

Primeiras tentativas detectaram uso incorreto de LoggerContext como AutoCloseable, tentativa de trocar MDC já definido em LoggingEvent e appender mantendo arquivo temporário aberto no Windows. Corrigidos com stop explícito, mapa mutável de fixture e reset/limpeza/restauração do sistema de logging. Nenhum incidente de credencial real. A configuração mínima foi marcada TestConfiguration para não entrar no component scan dos outros testes.

## Build completo

Comando: `.\mvnw.cmd -B -ntp spotless:apply verify`, com JDK 21.

BUILD SUCCESS às **14:51:00 UTC−03**, duração **10 min 29 s**. **793 testes, zero falhas, erros ou skips**: security-events 43; IAM 387 unitários + 286 integração; enforcer 44 unitários + 22 integração; fim a fim 11. Os dois gates JaCoCo reportaram cobertura atendida; regras de arquitetura incluídas. Os smokes opcionais pelo nome não fazem parte deste comando.

Na revisão durante a execução, acrescentou-se tratamento de mensagem nula e teste correspondente nos dois formatadores. O IAM já estava compilado na execução completa; por isso a revisão final foi recompilada/empacotada e validada separadamente:

```powershell
.\mvnw.cmd -B -ntp -pl iam-core,policy-enforcer -am spotless:apply '-Dtest=SafeOperationalLogFormatterTest,StructuredLoggingProfileTest,CorrelationIdFilterTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=NoSelectedIntegrationTests' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djacoco.skip=true' verify
```

BUILD SUCCESS às **14:51:47 UTC−03**, **21,389 s**, **11 testes** (7 IAM/4 enforcer), zero falhas/erros/skips. Esta seleção pula integração e JaCoCo explicitamente; não equivale a outro build completo. Contagens se sobrepõem e não devem ser somadas. A integração completa não foi repetida para o ajuste isolado de fallback nulo.

Conferidos **1.236 links locais** em README/docs, sem destinos ausentes, e whitespace dos oito arquivos novos de fonte/perfil; git diff --check aprovado. Logs de execução locais ignorados: .firecrawl/etapa10b3a-unit.log, .firecrawl/etapa10b3a-verify.log e .firecrawl/etapa10b3a-final.log.

## Limites e pendências

Hash pseudonimiza, não cifra nem autoriza; não usar em labels Prometheus. Códigos finitos: 9 IAM, 2 enforcer incluindo genérico. Templates novos/desconhecidos perdem detalhe. Sem garantia para stdout/stderr direto, appenders externos, logging anterior à configuração ou erros internos do Logback. Perfil desativado mantém comportamento anterior. Sem coleta/retensão centralizada, smoke opcional de observabilidade ou teste de tracing distribuído nesta entrega.

10B3b (HTTP) e 10B3c (outbox/AMQP) propostas e pendentes, assim como medição 10C, ajustes 10D, publicação/CI e aceite global da etapa 10.

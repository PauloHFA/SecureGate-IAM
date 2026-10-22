# Evidências — 10B3c3a: consumo no enforcer

- Data: 21/10/2026, America/Sao_Paulo.
- Base: be06e27; branch feat/outbox-trace-context.
- Estado: implementação e validação locais concluídas; incluída neste commit; push/CI remota pendentes.

## Escopo e divisão

A proposta 10B3c3 foi dividida em c3a (duas filas do enforcer) e c3b (auditoria em lote IAM), preservando a atomicidade do lote sem atribuir parent arbitrário. Nova bridge no enforcer, provider explícito opcional/perfil consumer-tracing, sampler local, helper ConsumerTrace no processamento/disposição e bootstrap opcional. JSON estruturado passa a sete campos com IDs restritos. Sem SDK em domínio/aplicação/security-events, sem exporter, sem nova migração, topologia, métricas ou alterações na stack persistente/.env.

## Verificação e ajustes

Primeira seleção unitária teve duas falhas e um erro: ausência de IDs no MDC no fixture SDK local, um caso de uppercase que usava apenas dígitos e reutilização de LoggingEvent no teste de formatter. Implementação passou a preencher/restaurar explicitamente os dois campos no escopo; fixture passou a usar letras para testar uppercase. Segunda seleção passou no helper, mas o teste novo do formatter reutilizava LoggingEvent com setMDCPropertyMap, que só pode ser chamado uma vez por evento; foi corrigido para criar eventos distintos, sem relaxar asserções. Tentativas intermediárias não são builds aprovados.

Seleção aprovada: BUILD SUCCESS às 20:42:22 UTC−03, 51,435 s, exit code nativo 0; **15 unitários/arquitetura + 24 integrações**, zero falhas/erros/skips. Depois dessa seleção, os testes existentes de perfil real e contexto opt-in foram estendidos para ativar o tracer também no bootstrap; o build completo verifica essas versões finais. JaCoCo desativado apenas na seleção.

Build completo: **BUILD SUCCESS às 20:53:30 UTC−03**, 10 min 42 s, exit code nativo **0**. **835 testes**, zero falhas/erros/skips: security-events 43, IAM 405 unitários + 299 integrações, enforcer 53 unitários + 24 integrações, PropagationIT ponta a ponta 11. Os **dois gates JaCoCo** foram atendidos e arquitetura aprovada. Totais do log desta execução, sem somar XMLs antigos de smokes.

Sem aviso de timeout de encerramento do fork nesta execução; não é correção da ressalva histórica Hikari. Nenhum código alterado depois de iniciar o build completo; apenas documentação de resultados. Não somar seleção com verify completo, pois se sobrepõem.

Conferência final: 24 arquivos novos/alterados UTF-8 sem BOM/whitespace final, git diff --check limpo e 1.366 links locais da documentação sem destino quebrado. Logs de execução permanecem no diretório ignorado .firecrawl, fora do Git.

```powershell
.\mvnw.cmd -B -ntp -pl policy-enforcer -am spotless:apply '-Dtest=ConsumerTraceTest,ConsumerTracingConfigurationTest,SafeOperationalLogFormatterTest,StructuredLoggingProfileTest,EnforcerArchitectureTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=RevocationConsumerIT,MetricsDisabledIT,MetricsEndpointIT,VaultKvBootstrapIT' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djacoco.skip=true' verify
.\mvnw.cmd -B -ntp verify
```

Novos testes: cinco do helper com SDK real; três de configuração; um de IDs no formatter; duas integrações com RabbitMQ/Redis reais. Integração de contexto Spring existente também verifica default sem tracer/provider e opt-in com tracer. Retry inicial é falha injetada no store, depois efeito Redis real; não simula queda de JVM. Poison → legacy no mesmo worker verifica rejeição/DLQ, raiz independente e limpeza do escopo. Não há teste novo único de toda a cadeia HTTP → outbox → consumidor; etapas anteriores verificam captura e publicação separadamente.

## Limites

10B3c3b (IAM audit.persist em lote), exporter/backend, retenção, medição e aceite global continuam pendentes. Incluída neste commit; push pendente, sem consulta de CI/merge. Stack persistente preservada. Smokes opt-in pelo nome não são implicados por verify e XMLs antigos não serão somados. Ressalva histórica de shutdown Hikari não é declarada corrigida.

[ADR-062](../adr/062-tracing-consumo-enforcer.md), [operação](../runbooks/tracing-consumidores-local.md), [guia](../GUIA-DE-IMPLEMENTACAO.md).

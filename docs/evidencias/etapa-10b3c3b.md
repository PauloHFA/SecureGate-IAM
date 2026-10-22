# Evidências — 10B3c3b: tracing de auditoria em lote

- Data: 21/10/2026, America/Sao_Paulo.
- Base: 103553e; branch feat/outbox-trace-context.
- Estado: implementação e validação locais concluídas; incluída neste commit; push/CI remota pendentes.

## Estado inicial e alterações

Não havia alterações pendentes para o commit solicitado antes de começar; a 10B3c3a já estava no commit 103553e. Nenhum commit vazio/push foi criado. Esta entrega acrescenta AuditConsumerTrace, conexão opcional do tracer no bootstrap e instrumentação do worker preservando API anterior e armazenamento do evento v1.

Raiz CONSUMER por lote com links de produtores distintos e nenhum parent arbitrário. Spans individuais somente para disposição inválida e fallback isolado, com parents explícitos. Escopo/MDC restaurados em finally; cinco atributos finitos, sem erros livres, payload, credenciais ou labels novas. Sem mudanças em aplicação/domínio/security-events, schema, permissões, dependências, .env ou stack persistente.

## Verificações

Seleção inicial unitária: 24 testes aprovados, incluindo seis novos do helper com SDK real. Depois foram acrescentados dois testes de worker e duas integrações; os resultados finais abaixo substituem a seleção inicial para a versão completa.

Seleção aprovada: **BUILD SUCCESS às 21:25:28 UTC−03**, 1 min 4 s, exit code nativo **0**. **26 unitários/arquitetura + 23 integrações**, zero falhas/erros/skips: AuditIngestionIT 10 e OutboxPublicationIT 13. Cobertura desativada apenas na seleção.

Build completo: **BUILD SUCCESS às 21:36:26 UTC−03**, 10 min 48 s, exit code nativo **0**. **845 testes**, zero falhas/erros/skips: security-events 43; IAM 413 unitários + 301 integrações; enforcer 53 unitários + 24 integrações; PropagationIT ponta a ponta 11. **Dois gates JaCoCo** e arquitetura atendidos. Totais do log desta execução, sem somar XMLs antigos ou a seleção sobreposta.

Nenhum código foi alterado após o início do build completo; apenas documentação. Conferência final: 16 arquivos UTF-8 novos/alterados sem BOM/whitespace final, 1.385 links locais válidos e git diff --check limpo. Logs/dump de execução ficam nos diretórios locais ignorados, fora do Git.

Na execução completa, após os testes IAM, ocorreu o aviso Surefire de encerramento forçado do fork 30 s após System.exit(0). O dump 2026-10-21T21-25-39_526-jvmRun1.dump mostra SpringApplicationShutdownHook em HikariPool.shutdown e threads connection-adder. É compatível com a ressalva histórica de shutdown; não estabelece causa conclusiva nem correção nesta entrega. Não houve alteração de timeout ou nova execução para ocultar o aviso. O reactor terminou com sucesso e nenhum teste falhou; o aviso de shutdown continua sendo uma limitação separada.

```powershell
.\mvnw.cmd -B -ntp -pl iam-core -am spotless:apply '-Dtest=AuditConsumerTraceTest,RabbitAuditWorkerTest,AuditConsumerConfigurationTest,IamArchitectureTest,ArchitectureRulesTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=AuditIngestionIT,OutboxPublicationIT' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djacoco.skip=true' verify
.\mvnw.cmd -B -ntp verify
```

AuditConsumerTraceTest: raízes/links deduplicados de parents distintos, String/LongString, contexto hostil/ambíguo/ausente, parent isolado restaurando batch/MDC, exceções sem texto em spans, sampler zero e default sem inspeção de headers. RabbitAuditWorkerTest: fluxo real do worker com SDK/canal simulado, conflito isolado com parent correto e ACK incerto limpando escopo antes da desconexão, além das regressões anteriores.

AuditIngestionIT: RabbitMQ/PostgreSQL reais e login de ingestão restrito. Novo teste verifica três mensagens (dois contexts e legacy), appendBatch único, rows/recibos/ACK e links. Outro injeta falha transitória antes do store na primeira tentativa e verifica redelivery, links preservados, nova raiz, persistência/recibo únicos e MDC limpo. Não é queda real de banco/JVM. Testes anteriores verificam rollback real, commit/ACK incerto, poison/late, privilégios e deduplicação sem tracing. OutboxPublicationIT permanece regressão do transporte.

## Limites e próximo aceite

Spans de lote representam relações por links; decode puro antecede o span do lote. Sem exporter/backend ou consulta de traces em produção, sem coleta conjunta sustentada, sem medição de capacidade. A proposta 10B4 torna explícito esse fechamento de observabilidade já pendente; depois seguem 10C/10D. A 10B3c3 fica instrumentada, mas o aceite global 10B permanece aberto.

Incluída neste commit; push/CI/merge pendentes; sem migração da stack persistente. Smokes opt-in pelo nome e XMLs antigos não são somados. Ressalva histórica de shutdown Hikari não é declarada corrigida. [ADR-063](../adr/063-tracing-auditoria-em-lote.md), [operação](../runbooks/tracing-consumidores-local.md), [guia](../GUIA-DE-IMPLEMENTACAO.md).

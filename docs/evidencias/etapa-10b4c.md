# Etapa 10B4c — validação integrada do tracing

Data local: 21/10/2026. Branch: `feat/tracing-backend`. Estado: implementada, validada e versionada em commit local; push e CI remota pendentes.

## Procedimento reproduzível

1. Executar `./mvnw -B -ntp -pl integration-tests -am '-Dtest=NoUnitTestsSelected' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=TracingJourneyIT' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djacoco.skip=true' verify` (no Windows, `./mvnw.cmd`). Docker e JDK 21 são necessários. O teste usa apenas contêineres descartáveis, portas aleatórias e credenciais geradas; não modifica a stack persistente.
2. Iniciar Tempo e Grafana provisionados pelos arquivos versionados da infraestrutura. Subir PostgreSQL, Redis, RabbitMQ, Vault, IAM e enforcer reais; reiniciar IAM com ingestão/consulta de auditoria habilitadas. Ambos os serviços exportam OTLP/HTTP para a porta loopback do Tempo e amostram 100% apenas nesta fixture sintética.
3. Criar um usuário via HTTP administrativo e bloqueá-lo. Esperar projeção Redis e consultar o `trace_parent` persistido no outbox. Buscar esse trace no Tempo e exigir `http.server`, `outbox.publish` e `security.consume`, com recursos `iam-core` e `policy-enforcer`.
4. Consultar o mesmo trace pelo proxy do datasource Tempo: sem credencial o Grafana retorna 401; com credencial gerada retorna o trace. Verificar que a senha de Grafana não aparece no payload consultado.
5. Pausar somente o Tempo descartável; bloquear outro usuário e confirmar o efeito Redis. Reativar o mesmo contêiner e aguardar readiness. Gerar dez novos bloqueios, espaçados em 2 s, conferindo para cada um projeção Redis e cadeia de três spans consultável. Buscar uma raiz `audit.consume.batch` e exigir ao menos um link de produtor.

## Resultado observado

`TracingJourneyIT`: **1 teste, 0 falhas, 0 erros, 0 skips**, 81,88 s; reactor de cinco módulos `BUILD SUCCESS` em 1 min 31 s, concluído às 23:20:55 UTC−03. A seleção valida o transporte real das duas aplicações, persistência/consulta no Tempo, autorização no Grafana, link de lote de auditoria, continuidade do negócio durante indisponibilidade do receptor e coleta de dez eventos após retorno. Os artefatos brutos ficam em `integration-tests/target/failsafe-reports` e não devem ser publicados sem revisão.

`./mvnw.cmd -B -ntp verify`: reactor `BUILD SUCCESS` às 23:34:45 UTC−03, 12 min 45 s; somatórios Maven por módulo: **887 testes, 0 falhas, 0 erros, 0 skips** (43 + 432 + 301 + 72 + 25 + 14). Dois gates JaCoCo atendidos e teste de arquitetura IAM aprovado. As suites anteriores `PropagationIT` (11 casos) e `TracingBackendIT` (2 casos) passaram junto com `TracingJourneyIT` (1 caso). O aviso histórico de encerramento do fork Surefire IAM reapareceu, sem falha no reactor.

## Limites e próximos passos

O patamar de ~20 s e dez operações é uma verificação funcional limitada, **não** benchmark, SLA, teste de capacidade ou prova de retenção/expurgo após 24 h. Spans emitidos durante a pausa podem se perder e não são reconstituídos; o teste exige somente novos traces após recuperação. A consulta direta ao Tempo local permanece sem autenticação; 401 vale para o proxy Grafana. A fixture não comprova coleta simultânea sustentada de Prometheus, logs e tracing em toda a stack com exporters PostgreSQL/Redis/RabbitMQ/Vault, nem revisão visual da UI. O aceite global 10B continua aberto até essa validação de sinais conjuntos; 10C executará medição de desempenho separada.

Não houve alteração de schema, migração, segredos estáticos ou configuração da stack persistente. A ressalva histórica do fork IAM/Hikari permanece registrada em [10B4b](etapa-10b4b.md).

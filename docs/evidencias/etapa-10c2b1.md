# Etapa 10C2b1 — introspecção autenticada de token ativo

Data: 22/10/2026 (UTC−03). Branch `feat/tracing-backend`, base `0ea1957`. Implementação versionada em commit local; push e CI remota pendentes.

## Método

O plano JMeter mede apenas `POST /oauth2/introspect` com Basic do cliente proprietário e token ativo. Antes de cada fase, o runner emite um token novo por cliente sintético, exige validade maior que a fase mais 15 s e remove o CSV privado após o processo JMeter. O contrato exige HTTP 200, `active: true` e `client_id` correto. Os resultados preservam as três fases, janelas do patamar e amostras IAM da 10C2a. A preparação de tokens não integra a latência ou taxa das amostras.

## Validação local

Seleção descartável: `./mvnw.cmd -B -ntp -pl integration-tests -am spotless:apply '-Dtest=NoSelectedUnitTests' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=TokenBaselineSmoke' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djacoco.skip=true' '-Dsecuregate.jmeter.home=C:/Projetos/SecureGate IAM/.firecrawl/tools/apache-jmeter-5.6.3' '-Dsecuregate.load.scenario=introspection' '-Dsecuregate.load.steady.seconds=60' '-Dsecuregate.load.window.seconds=10' verify`.

Resultado: **1 teste, 0 falhas, 0 erros, 0 skips; BUILD SUCCESS** às 11:43:45 UTC−03, em 1 min 53 s. No patamar de 60 s com duas threads: 1.330 introspecções ativas, zero falhas, taxa 22,17 req/s e p95 103 ms. Cinco janelas observadas de 10 s cobriram 1.116 introspecções, variando entre 21 e 23 req/s e p95 entre 96 e 115 ms. As amostras da cauda ficaram no agregado. Pico de working set IAM: 367,63 MiB. O teste conferiu ausência de segredos/Basic/JWT nos artefatos persistidos e remoção dos CSVs temporários de tokens.

Uma seleção curta 3/5/3 s também passou, com 1 teste e zero falhas às 11:41:41 UTC−03. O primeiro comando incremental falhou antes de iniciar a fixture por classes de teste locais inconsistentes; `clean` recompilou os módulos e a seleção passou. Esse erro anterior não conta como validação da funcionalidade. Após reforçar a remoção dos CSVs temporários no bloco de encerramento, a seleção curta de introspecção passou novamente com 1 teste e zero falhas às 11:46:31 UTC−03, em 1 min 03 s. A regressão final curta da emissão de token também passou com 1 teste e zero falhas às 11:47:42 UTC−03, em 1 min. Os relatórios ficam em `integration-tests/target/load-capacity/`, ignorado pelo Git.

## Limites e próximo passo

Introspecção HTTP não é o caminho de validação local com cache do recurso. Portanto, o p95 de 103 ms não pode ser comparado diretamente à meta p95 < 20 ms daquela validação. Duas threads não determinam capacidade máxima nem taxa ofertada independente. O patamar de 60 s ainda não demonstra estabilidade prolongada; os recursos de PostgreSQL, Redis, RabbitMQ e Vault não foram amostrados. O `verify` completo não foi repetido após a 10C1. A 10C2b2 medirá validação no recurso, revogação, auditoria e dependências. [Operação](../runbooks/medicao-introspeccao-token-local.md).

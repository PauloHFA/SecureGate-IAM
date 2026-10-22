# Etapa 10B5b — exporters na coleta conjunta

Data: 22/10/2026 (UTC−03). Branch: `feat/tracing-backend`; base `a8ccaed`. Implementação versionada em commit local; push e CI remota pendentes.

## Cenário e verificações

O [teste integrado](../../integration-tests/src/test/java/com/securegate/e2e/JointSignalsIT.java) reutiliza a janela da 10B5a e adiciona PostgreSQL, Redis, RabbitMQ e Vault ao mesmo Prometheus. Os exporters e configurações usam as imagens/templates versionados. No ambiente descartável, o PostgreSQL recebe `pg_monitor`/somente leitura, o Redis recebe ACL sem chaves, e o Vault recebe token mínimo gerado, com 403 sem token e 200 autenticado na rota de métricas. O RabbitMQ usa configuração de fixture sem importação das definições, pois essa importação impede a criação do usuário temporário pelo Testcontainers.

Os seis jobs devem permanecer em `up=1` ao longo de 12 bloqueios espaçados em 5 s. Também são exigidos `pg_up=1`, `redis_up=1`, `vault_core_unsealed=1`, série agregada de fila RabbitMQ, pelo menos três amostras por dependência na última janela de um minuto, dashboards das quatro dependências via Grafana autenticado, 12 ACKs, traces de começo/meio/fim e logs JSON restritos dos dois JARs.

Seleção: `./mvnw.cmd -B -ntp -pl integration-tests -am spotless:apply '-Dtest=NoUnitTestsSelected' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=JointSignalsIT' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djacoco.skip=true' verify`. Resultado: **1 teste, 0 falhas, 0 erros, 0 skips**; caso em 158 s, reactor `BUILD SUCCESS` em 2 min 46 s, às 10:32:29 UTC−03.

Primeiro `./mvnw.cmd -B -ntp verify`: **BUILD FAILURE** no IAM após 9 min 18 s, antes de chegar ao módulo E2E. Durante `VaultDatabaseIT`, o Docker Desktop reiniciou: os containers persistentes reapareceram com uptime de segundos, as conexões dos containers descartáveis foram recusadas e `VaultKvBootstrapIT` recebeu HTTP 500 do daemon ao criar outro PostgreSQL. Resultado IAM: 300 integrações executadas, 1 falha e 8 erros; módulos enforcer/E2E não executados. Este resultado não é atribuído à alteração 10B5b nem tratado como build aprovado.

Repetição isolada: `./mvnw.cmd -B -ntp -pl iam-core -am '-Dtest=NoUnitTestsSelected' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=VaultDatabaseIT,VaultKvBootstrapIT' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djacoco.skip=true' verify`: **14 testes, 0 falhas, 0 erros, 0 skips; BUILD SUCCESS** em 2 min 10 s, às 10:44:32 UTC−03. O `verify` completo foi reiniciado com Docker estável; resultado a registrar após conclusão.

Na segunda tentativa completa, IAM e enforcer passaram, mas a sessão de execução foi interrompida durante a janela do `JointSignalsIT` no E2E, sem resumo Maven final. Ela não conta como validação integral.

Terceiro `./mvnw.cmd -B -ntp verify`: **BUILD SUCCESS** às 11:15:55 UTC−03, em 15 min 23 s. Totais Maven por módulo: **888 testes, 0 falhas, 0 erros, 0 skips** (43 + 432 + 301 + 72 + 25 + 15). Os dois gates JaCoCo e o teste de arquitetura IAM passaram. `JointSignalsIT` (1), `PropagationIT` (11), `TracingBackendIT` (2) e `TracingJourneyIT` (1) passaram juntos; o novo caso levou 159,5 s. A ressalva histórica de shutdown do fork IAM não foi tratada nesta etapa.

## Decisão e limites

A integração funcional local dos quatro exporters com os três sinais do núcleo está demonstrada. O aceite global 10B fica **parcial** quanto à operação: os logs são locais, sem agregação central, e a janela de um minuto não comprova retenção nem estabilidade sustentada. A 10C medirá capacidade e uso de recursos. Nenhuma stack persistente, schema ou segredo estático foi alterado. [Procedimento](../runbooks/coleta-conjunta-dependencias-local.md).

# Coleta conjunta local das dependências — 10B5b

O `JointSignalsIT` executa uma janela funcional de um minuto com IAM e enforcer reais, PostgreSQL, Redis, RabbitMQ e Vault descartáveis, exporters de banco/cache, Prometheus, Grafana e Tempo. Credenciais são geradas por execução; a stack persistente e o `.env` não são usados.

## Reproduzir

1. Disponibilizar Docker Desktop e JDK 21. Na raiz do projeto, executar `./mvnw.cmd -B -ntp -pl integration-tests -am '-Dtest=NoUnitTestsSelected' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=JointSignalsIT' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djacoco.skip=true' verify` (Linux/macOS: `./mvnw`).
2. O teste monta a configuração RabbitMQ de métricas própria da fixture, o listener Vault protegido e os templates versionados dos seis jobs. Cria login PostgreSQL somente leitura, ACL Redis sem acesso a chaves e token Vault com política mínima; arquivos temporários de credenciais são montados somente onde necessários.
3. Confere `up=1` para IAM, enforcer, PostgreSQL, Redis, RabbitMQ e Vault, além de `pg_up=1`, `redis_up=1`, `vault_core_unsealed=1` e da série `rabbitmq_queue_messages_ready`. Exige leitura autenticada dos datasources Prometheus/Tempo e dos quatro dashboards de dependências.
4. Executa doze bloqueios administrativos espaçados em 5 s; verifica saúde dos seis jobs durante o patamar, amostras recentes, 12 ACKs do enforcer, traces do começo/meio/fim pelo Tempo e proxy Grafana e esquema restrito dos logs locais.
5. O JUnit encerra os recursos descartáveis. Em falhas, inspecionar `integration-tests/target/failsafe-reports` e os logs em `integration-tests/target/e2e`, revisando qualquer artefato antes de compartilhar.

## Interpretação

A execução comprova integração e coleta periódica em um cenário sintético curto. Os logs JSON são verificados nos processos locais; não há coletor central de logs nesta fixture. O teste não mede capacidade, disponibilidade prolongada nem retenção de dados. Esses limites devem permanecer explícitos no aceite 10B e na futura medição 10C.

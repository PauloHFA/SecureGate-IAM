# Coleta conjunta local do núcleo — 10B5a

Esta etapa verifica, por uma janela funcional de um minuto, métricas Prometheus, logs JSON restritos e traces OTLP dos processos reais IAM/enforcer. A fixture usa imagens e templates versionados, contêineres descartáveis e credenciais geradas; não acessa a stack persistente do desenvolvedor.

## Reproduzir

1. Com Docker Desktop e JDK 21 disponíveis, executar na raiz: `./mvnw.cmd -B -ntp -pl integration-tests -am '-Dtest=NoUnitTestsSelected' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=JointSignalsIT' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djacoco.skip=true' verify` (Linux/macOS: `./mvnw`). Não usar `.env` nem tokens reais.
2. O teste inicia Tempo e os serviços de negócio descartáveis. IAM/enforcer recebem perfis `structured-logs,http-tracing`/`structured-logs,consumer-tracing`, exporter OTLP loopback, amostragem 1 e endpoints Prometheus protegidos por tokens distintos. Inicia Prometheus com scrape de 5 s e Grafana com ambos os datasources provisionados.
3. Espera `up=1` para IAM/enforcer e exige datasource autenticado; executa doze operações de criação/bloqueio de usuário, espaçadas em 5 s. Cada operação só passa após projeção Redis, e os scrapes continuam saudáveis no patamar.
4. Confere pelo menos oito amostras recentes de `up` para cada serviço, contador de ACK do enforcer, traces HTTP → publicação → consumo no começo/meio/fim da janela e leitura desses traces pelo proxy Grafana. Verifica que novos logs de ambos os processos seguem exatamente os sete campos JSON permitidos e não contêm os tokens de scrape.
5. O JUnit encerra processos e contêineres descartáveis e remove os arquivos temporários de credenciais. Se falhar, consultar o relatório Failsafe em `integration-tests/target/failsafe-reports` e os logs locais de teste em `integration-tests/target/e2e`; revisar antes de compartilhar qualquer artefato.

## Interpretação

`up=1` e amostras ao longo da janela comprovam coleta periódica nesta fixture; não comprovam latência-alvo, throughput, consumo estável de recursos nem retenção de 24 h. Os logs são verificados no fluxo local dos processos, sem agregador central. A amostragem 1 só é usada com massa sintética. Os exporters PostgreSQL/Redis/RabbitMQ/Vault continuam cobertos individualmente por seus smokes, mas ainda precisam de execução simultânea na 10B5b antes de fechar o aceite global 10B. A medição de desempenho permanece na 10C.

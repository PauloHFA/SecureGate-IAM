# Etapa 10C2b2a — recursos das dependências durante carga

Data: 22/10/2026 (UTC−03). Branch `feat/tracing-backend`, base `9e13ed5`. Implementação versionada em commit local; push e CI remota pendentes.

## Método

O runner recebe IDs completos de containers, valida formato/unicidade e executa `docker stats --no-stream` em job por fase. Registra timestamp UTC, ID abreviado, CPU%, uso/limite de memória e quantidade de processos em CSV local. O resumo guarda quantidade de pontos e picos de CPU/memória por ID. O teste passa PostgreSQL, Redis, RabbitMQ e Vault descartáveis nessa ordem. A amostragem é aproximada, não contínua; o início inclui inicialização do JMeter e o fim pode incluir seu encerramento. Métricas de dependência são contexto da carga, não latência atribuída a cada requisição.

## Validação local

Seleção curta: `./mvnw.cmd -B -ntp -pl integration-tests -am spotless:apply '-Dtest=NoSelectedUnitTests' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=TokenBaselineSmoke' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djacoco.skip=true' '-Dsecuregate.jmeter.home=C:/Projetos/SecureGate IAM/.firecrawl/tools/apache-jmeter-5.6.3' verify`. **1 teste, 0 falhas, 0 erros, 0 skips; BUILD SUCCESS** às 13:09:58 UTC−03, em 1 min 05 s. Os quatro containers produziram duas amostras por fase. O teste verificou IDs, pontos, picos não negativos e ausência de credenciais/JWT nos artefatos.

Seleção de 60 s: o mesmo comando, acrescentando `'-Dsecuregate.load.steady.seconds=60' '-Dsecuregate.load.window.seconds=10'`. **1 teste, 0 falhas, 0 erros, 0 skips; BUILD SUCCESS** às 13:12:10 UTC−03, em 1 min 59 s. No patamar de emissão com duas threads: 1.434 emissões, zero falhas, 23,9 req/s e p95 103 ms. As cinco janelas completas cobriram parte do patamar. Cada um dos quatro containers teve 16 snapshots. Picos por ordem PostgreSQL/Redis/RabbitMQ/Vault:

| Componente | CPU máxima observada | Memória máxima observada |
| --- | ---: | ---: |
| PostgreSQL | 6,65% | 60,16 MiB |
| Redis | 0,48% | 3,61 MiB |
| RabbitMQ | 2,36% | 104,8 MiB |
| Vault | 12,97% | 51,74 MiB |

Pico de working set IAM: 352,99 MiB. Os relatórios ficam em `integration-tests/target/load-capacity/`, ignorado pelo Git. A regressão curta de introspecção com a coleta de containers passou com 1 teste e zero falhas às 13:13:31 UTC−03, em 1 min 12 s. A repetição após ajuste de limpeza dos jobs e do CSV temporário também passou com 1 teste e zero falhas às 13:14:39 UTC−03, em 1 min 02 s.

A coleta Docker foi executada independentemente da coleta do processo IAM com o mesmo comando curto acrescido de `'-Dsecuregate.load.omitIamPid=true'`. **1 teste, 0 falhas, 0 erros, 0 skips; BUILD SUCCESS** às 13:16:02 UTC−03, em 1 min 02 s. O teste confirmou `iamProcess: null` e quatro séries de containers em cada fase. A sintaxe PowerShell, `git diff --check` e os links dos documentos novos também foram verificados.

## Limites e próximo passo

Este ensaio usa somente duas threads e 60 s de patamar; não demonstra capacidade de 300 req/s, estabilidade prolongada, saturação nem causalidade entre picos de recursos e latência. CPU/memória do enforcer não foram amostradas, pois ele não recebe tráfego de token neste cenário. Os números representam containers descartáveis desta máquina, não a stack persistente. O `verify` completo ainda não foi repetido após a 10C1. A 10C2b2b deve medir validação no recurso, revogação e auditoria sem misturar setup/negações nos percentis. [Operação](../runbooks/medicao-recursos-dependencias-carga-local.md).

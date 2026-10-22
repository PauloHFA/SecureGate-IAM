# Etapa 10C2a — janelas temporais da emissão de token

Data: 22/10/2026 (UTC−03). Branch `feat/tracing-backend`, base `09865d5`. Implementação versionada em commit local; push e CI remota pendentes.

## Método

O patamar `client_credentials` continua em carga fechada JMeter, com duas threads e dados sintéticos privados. O runner conserva o agregado da 10C1 e calcula por janela completa contagem, falhas, taxa efetiva e p95, usando o timestamp de início da amostra como referência. Só considera uma janela completa quando há uma amostra observada após seu fim; a cauda fica no agregado. Janelas vazias registram taxa zero e p95 nulo. A opção `-WindowSeconds` define a largura e não pode superar o patamar.

## Validação local

Seleção Maven: `./mvnw.cmd -B -ntp -pl integration-tests -am spotless:apply '-Dtest=NoSelectedUnitTests' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=TokenBaselineSmoke' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djacoco.skip=true' '-Dsecuregate.jmeter.home=C:/Projetos/SecureGate IAM/.firecrawl/tools/apache-jmeter-5.6.3' '-Dsecuregate.load.steady.seconds=60' '-Dsecuregate.load.window.seconds=10' verify`.

Resultado: **1 teste, 0 falhas, 0 erros, 0 skips; BUILD SUCCESS**, às 11:32:33 UTC−03, em 2 min 14 s. O patamar de 60 s produziu 1.312 emissões, zero falhas, taxa agregada 21,87 req/s e p95 121 ms. Cinco janelas observadas de 10 s cobriram 1.076 emissões; a taxa variou de 18,6 a 24,4 req/s e o p95 de 96 a 144 ms. As demais 236 emissões ficaram no agregado, incluindo a cauda não coberta por janela completa. Pico de working set do processo IAM: 368,25 MiB. O teste também conferiu contrato, recursos por fase e ausência de credenciais sintéticas e JWT nos artefatos UTF-8/UTF-16.

A primeira seleção curta de 5 s revelou uma janela final incompleta com taxa artificialmente baixa; o método foi corrigido para excluir a janela quando não existe amostra após seu fim. A seleção curta foi repetida após uma asserção adicional do limite temporal: **1 teste, 0 falhas, 0 erros, 0 skips; BUILD SUCCESS**, às 11:33:48 UTC−03, em 1 min 03 s. Após otimizar a distribuição das amostras em uma única passagem, a seleção curta passou novamente: **1 teste, 0 falhas, 0 erros, 0 skips; BUILD SUCCESS**, às 11:35:08 UTC−03, em 58 s. Os artefatos permanecem em `integration-tests/target/load-capacity/`, ignorado pelo Git.

## Limites e próximo passo

As cinco janelas cobrem observação parcial do patamar configurado. O ensaio com duas threads **não** oferece 300 req/s independentemente e não prova capacidade máxima nem SLA. O p95 ficou abaixo de 200 ms neste perfil específico, sem provar desempenho em maior carga. Recursos temporais foram coletados somente para o IAM; PostgreSQL, Redis, RabbitMQ e Vault não foram medidos. O `verify` completo ainda não foi repetido após 10C1/10C2a. A 10C2b deve medir os demais fluxos, recursos das dependências e perfis com carga controlada. [Procedimento](../runbooks/medicao-emissao-token-local.md).

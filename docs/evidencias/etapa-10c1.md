# Etapa 10C1 — linha de base curta de emissão de token

Data: 22/10/2026 (UTC−03). Branch `feat/tracing-backend`, base `59843d7`. Implementação versionada em commit local; push e CI remota pendentes.

## Método

O plano JMeter usa apenas emissão `client_credentials` com cliente sintético criado pela API; a recusa `invalid_client` permanece no smoke funcional 10A1. Três execuções sequenciais usam 1 / 2 / 1 threads, duração configurável e resultado por fase. O runner calcula contagem, falhas, taxa, p50/p95/p99 e, quando recebe PID, amostra CPU e memória do IAM. Senhas, Basic e JWT são procurados nos artefatos UTF-8 e UTF-16 do teste opt-in. A fixture não usa o `.env` nem a stack persistente.

## Validação local

Seleção: `./mvnw.cmd -B -ntp -pl integration-tests -am spotless:apply '-Dtest=NoSelectedUnitTests' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=TokenBaselineSmoke' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djacoco.skip=true' '-Dsecuregate.jmeter.home=C:/Projetos/SecureGate IAM/.firecrawl/tools/apache-jmeter-5.6.3' verify`.

Resultado final: **1 teste, 0 falhas, 0 erros, 0 skips; BUILD SUCCESS** às 11:23:35 UTC−03, em 57,925 s. O ensaio durou 3 / 5 / 3 s nas fases, com 1 / 2 / 1 threads:

| Fase | Emissões | Falhas | Taxa efetiva | p95 | Pico working set IAM |
| --- | ---: | ---: | ---: | ---: | ---: |
| Aquecimento | 26 | 0 | 8,67 req/s | 132 ms | 321,28 MiB |
| Patamar | 96 | 0 | 19,2 req/s | 101 ms | 329,48 MiB |
| Redução | 29 | 0 | 9,67 req/s | 90 ms | 329,45 MiB |

O relatório `summary.json` ficou em `integration-tests/target/load-capacity/` (ignorado pelo Git); nenhuma credencial sintética nem JWT foi encontrada nos artefatos examinados. Antes do resultado final, a primeira seleção detectou saída JMeter misturada ao JSON e a segunda detectou leitura incorreta do console UTF-16. Ambas foram corrigidas e repetidas; não contam como validação aprovada.

## Limites e próximo passo

O patamar de 5 s serve para verificar o método. A taxa de 19,2 req/s está **abaixo** da referência de 300 req/s; não representa a capacidade máxima, porque a oferta foi limitada a duas threads. O p95 de 101 ms nesta amostra não demonstra cumprimento sustentado da meta de 200 ms. Cada fase usa outro processo JMeter, o que cria intervalo entre fases. CPU/memória representam apenas o IAM e a capacidade Docker foi registrada como metadado; não há consumo temporal completo de PostgreSQL, Redis, RabbitMQ e Vault. A 10C2 medirá patamares longos e os demais fluxos. [Operação](../runbooks/medicao-emissao-token-local.md).

# Etapa 10B5a — três sinais no núcleo IAM/enforcer

Seleção em 21/10/2026; build completo em 22/10/2026 (hora local). Branch: `feat/tracing-backend`. Estado: implementada, validada e versionada em commit local; push/CI remota pendentes.

## Cenário executado

O [teste integrado](../../integration-tests/src/test/java/com/securegate/e2e/JointSignalsIT.java) inicia IAM/enforcer, PostgreSQL, Redis, RabbitMQ, Vault, Prometheus, Grafana e Tempo descartáveis. Usa as imagens/configurações versionadas do projeto, tokens gerados para cada scrape e endpoint OTLP no loopback. Os processos de aplicação rodam com perfis de tracing e logs JSON; a amostragem 100% vale apenas para esta fixture sintética.

Após os dois scrapes retornarem `up=1`, gera 12 bloqueios administrativos com intervalo de 5 s. Cada bloqueio exige efeito Redis e os scrapes são consultados durante o patamar. Ao final, o teste exige pelo menos oito amostras de `up` na última janela de um minuto para cada serviço e pelo menos 12 ACKs no contador do enforcer. Traces do primeiro, sexto e último bloqueio devem conter os spans HTTP IAM, produtor outbox e consumidor enforcer, e ser consultáveis pelo proxy Grafana autenticado. Logs novos de ambos os JARs devem ser JSON com os sete campos restritos, sem tokens de scrape. O Grafana recusa acesso anônimo ao datasource Prometheus.

Comando de seleção: `./mvnw.cmd -B -ntp -pl integration-tests -am '-Dtest=NoUnitTestsSelected' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=JointSignalsIT' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djacoco.skip=true' verify`.

Resultado da seleção: **1 teste, 0 falhas, 0 erros, 0 skips**; teste em 125,4 s, reactor `BUILD SUCCESS` em 2 min 17 s, concluído às 23:51:21 UTC−03. O teste de 10B4c e os smokes individuais permanecem como cobertura complementar.

`./mvnw.cmd -B -ntp verify`: reactor `BUILD SUCCESS` em 22/10/2026 às 00:07:30 UTC−03, 14 min 46 s; somatórios Maven por módulo: **888 testes, 0 falhas, 0 erros, 0 skips** (43 + 432 + 301 + 72 + 25 + 15). Dois gates JaCoCo atendidos, teste de arquitetura IAM aprovado. `JointSignalsIT` (1 caso), `PropagationIT` (11), `TracingBackendIT` (2) e `TracingJourneyIT` (1) passaram juntos. A ressalva histórica de shutdown do fork IAM permanece sem mudança de contrato.

## Limites e pendências

Um minuto e doze eventos constituem uma janela funcional, não benchmark de capacidade ou estabilidade prolongada. A verificação de logs ocorre nos arquivos de saída locais; não foi implantado coletor central de logs. A fixture não sobe simultaneamente os exporters PostgreSQL, Redis, RabbitMQ e Vault, nem comprova seus dashboards sob carga conjunta. Isso fica para a 10B5b; o aceite global 10B e a medição 10C permanecem abertos. Nenhum schema, ACL, segredo estático ou stack persistente foi alterado.

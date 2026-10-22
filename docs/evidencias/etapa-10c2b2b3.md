# Etapa 10C2b2b3 — medição de auditoria centralizada

Data: 22/10/2026 (UTC−03). Branch `feat/load-audit-baseline`. Implementação local na nova branch.

## Método

O cenário `audit` expande o conjunto de testes de carga JMeter para avaliar o pipeline de auditoria de ponta a ponta sem misturar etapas no mesmo medidor:
1. **Produção (`audit.produce.call`):** chamadas HTTP `POST /api/admin/users` com token de administração Bearer e cabeçalho `X-Correlation-Id`. Cria o usuário na base relacional e armazena o evento correspondente na tabela outbox na mesma transação ACID.
2. **Visibilidade centralizada (`audit.visibility.observed`):** polling via `GET /api/admin/audit/events?from=...&to=...&eventType=identity.user.created&correlationId=...&limit=1` até que o evento publicado pelo outbox publisher e processado pelo consumidor assíncrono RabbitMQ apareça no repositório particionado com cursor para a próxima página (`nextCursor`). Mede o tempo decorrido do pipeline assíncrono.
3. **Consulta paginada (`audit.cursor.query`):** chamada direta à API de auditoria com `cursor=<nextCursor>`, recuperando o próximo evento da partição e validando integridade estrutural, correlação e ordem cronológica decrescente.

Adicionalmente, foi implementado o roteamento via `host.testcontainers.internal` com `Testcontainers.exposeHostPorts(...)` e `.withAccessToHost(true)` em `JointSignalsIT` e `DependencyMetricsFixture`, eliminando falhas de conexão cruzada no Docker em ambientes Linux (GitHub Actions).

## Validação local

Comando executado:
```powershell
.\mvnw.cmd -B -ntp -pl integration-tests -am '-Dtest=NoSelectedUnitTests' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=TokenBaselineSmoke' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djacoco.skip=true' '-Dsecuregate.jmeter.home=C:/Projetos/SecureGate IAM/.firecrawl/tools/apache-jmeter-5.6.3' '-Dsecuregate.load.scenario=audit' verify
```

Resultado: **1 teste executado, 0 falhas, 0 erros, 0 ignorados; BUILD SUCCESS** em 1 min 24 s (tempo total 1 min 30 s).

Resumo das métricas no regime permanente (`steady`, 10 s, 2 threads):
- Produção (`audit.produce.call`): 36 requisições, 0 falhas, 3.6 req/s, p50 = 62 ms, p95 = 908 ms.
- Visibilidade (`audit.visibility.observed`): 16 observações, 0 falhas, p50 = 882 ms, p95 = 909 ms (meta < 5000 ms atendida: `steadyPropagationMeetsTarget = true`).
- Consulta de cursor (`audit.cursor.query`): 16 consultas, 0 falhas, 1.6 req/s, p50 = 11 ms, p95 = 16 ms.
- Monitoramento de recursos: processo IAM coletado a cada segundo (pico 336 MiB working set, 34% CPU média); 4 containers (PostgreSQL, Redis, RabbitMQ e Vault) coletados sem perdas.
- Sanitização de artefatos: arquivos de resultado verificados sem vazamento de token administrativo, credenciais ou JWTs.

## Limites

A medição em ambiente local isolado reflete a infraestrutura local (Docker Desktop / loopback). A separação em três unidades assegura que a latência de consumo assíncrono do RabbitMQ não degrada artificialmente a taxa de chamadas do endpoint de produção (`audit.produce.call`) nem as consultas pontuais de navegação por cursor (`audit.cursor.query`). A próxima divisão abordará **10C3 — Consolidação e runbook unificado de carga**. [Operação](../runbooks/medicao-auditoria-centralizada-local.md).

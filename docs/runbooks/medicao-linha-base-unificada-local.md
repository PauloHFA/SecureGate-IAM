# Medição de linha de base unificada e comparativa de carga — 10C3

Este runbook consolida a execução comparativa dos cinco fluxos operacionais de carga do SecureGate IAM sob uma única automação homogênea ([`Run-ComparativeBaseline.ps1`](../../load-tests/jmeter/Run-ComparativeBaseline.ps1)):
1. **Emissão de tokens (`issuance`):** `POST /oauth2/token` via Client Credentials (`token.success`).
2. **Introspecção HTTP (`introspection`):** `POST /oauth2/introspect` autenticado pelo cliente proprietário (`introspection.active`).
3. **Validação em recurso protegido (`resource`):** `GET /api/resources/account` com Bearer obtido via PKCE (`resource.validation.success`).
4. **Revogação e propagação (`revocation`):** `POST /oauth2/revoke` com monitoramento rápido até HTTP 401 no recurso (`token.revoke.call` e `revocation.observed_refusal`).
5. **Auditoria centralizada (`audit`):** criação transacional de usuário com outbox (`audit.produce.call`), visibilidade assíncrona após processamento RabbitMQ (`audit.visibility.observed`) e navegação paginada (`audit.cursor.query`).

Todos os cenários compartilham a mesma infraestrutura de medição, limites temporais, concorrência uniforme por thread, janelas móveis no regime estacionário e telemetria de processo IAM e containers Docker.

## Verificação descartável automatizada

A validação automatizada descartável integra a inicialização da stack completa (PostgreSQL, Redis, RabbitMQ, Vault e IAM real) via Testcontainers, parametrização de políticas/risco, criação de massas sintéticas isoladas e execução sequencial dos cinco cenários:

```powershell
.\mvnw.cmd -B -ntp -pl integration-tests -am spotless:apply '-Dtest=NoSelectedUnitTests' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=ComparativeBaselineSmoke' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djacoco.skip=true' '-Dsecuregate.jmeter.home=C:/Tools/apache-jmeter-5.6.3' verify
```

O teste confirma:
- Execução sequencial dos cinco cenários sem erros em nenhuma das três fases (aquecimento, regime estacionário e desaceleração).
- Geração dos relatórios individuais de cada cenário e do relatório consolidado unificado (`comparative-summary.json` e `comparative-summary.csv`).
- Atendimento das metas de propagação de revogação (< 2000 ms) e visibilidade de auditoria (< 5000 ms).
- Amostragem contínua de recursos do processo IAM e dos 4 containers de dependência.
- Ausência total de vazamento de credenciais administrativas, senhas, chaves ou tokens JWT nos artefatos gerados.

## Execução manual do comparativo

Em um ambiente local dedicado com o IAM iniciado e o token administrativo salvo em arquivo (`admin-token.txt`), executar na raiz:

```powershell
.\load-tests\jmeter\Run-ComparativeBaseline.ps1 -JMeterHome C:/Tools/apache-jmeter-5.6.3 -BaseUri http://127.0.0.1:8080/ -AdminTokenFile load-tests/.local/admin-token.txt -OutputDirectory load-tests/.local/comparative-001 -Threads 2 -WarmupSeconds 10 -SteadySeconds 60 -WindowSeconds 10 -CooldownSeconds 10
```

Para monitorar CPU e memória durante a carga:
- `-IamProcessId <PID>`: coleta memória working set, memória privada e % de CPU do processo Java da aplicação.
- `-DockerContainerIds <ID1,ID2,ID3,ID4>`: coleta uso de CPU e memória dos containers PostgreSQL, Redis, RabbitMQ e Vault.

## Artefatos e relatórios gerados

No diretório de saída especificado:
- `comparative-summary.json`: matriz consolidada com parâmetros de execução, taxas efetivas, percentis p50/p95/p99 por fluxo e conformidade de metas.
- `comparative-summary.csv`: tabela resumida para comparação imediata em planilhas ou ferramentas de BI.
- `<cenario>/`: subdiretório individual por cenário contendo o `summary.json`, `samples.csv`, `dashboard/` HTML e logs de telemetria.

## Análise comparativa e interpretação

A consolidação unificada permite analisar os seguintes compromissos arquiteturais:
- **Custo de emissão vs validação:** compara a sobrecarga criptográfica de assinar JWTs via Vault Transit (`issuance`) contra a validação leve no recurso (`resource`).
- **Validação local vs introspecção HTTP:** contrasta o custo de decodificação e verificação de revogação no gateway/enforcer (`resource`) com a introspecção remota síncrona (`introspection`).
- **Propagação de revogação vs visibilidade de auditoria:** avalia o atraso de expiração de cache no Redis para negação rápida (< 2 s) contra o processamento em lote da auditoria assíncrona no RabbitMQ e persistência em partições PostgreSQL (< 5 s).
- **Consumo de recursos:** mapeia quais fluxos exigem maior consumo do pool de conexões do PostgreSQL e utilização do RabbitMQ.


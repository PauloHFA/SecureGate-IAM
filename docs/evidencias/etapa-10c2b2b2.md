# Etapa 10C2b2b2 — medição de revogação e propagação

Data: 22/10/2026 (UTC−03). Branch `feat/load-revocation-baseline`, base `bef3e67`. Implementação local na nova branch.

## Método

O cenário `revocation` estende a linha de base de carga para medir de forma estritamente separada:
1. **Preparação (`pkce.journey.success`):** emissão de um token humano fresco e credenciais Basic do cliente confidencial via PKCE, mantidos em memória. Isolado em `setupSamples`, fora dos percentis de revogação.
2. **Chamada de revogação (`token.revoke.call`):** `POST /oauth2/revoke` autenticado pelo cliente proprietário com `token_type_hint=access_token`. Mede tempo de resposta e sucesso HTTP 200.
3. **Observação de recusa (`revocation.observed_refusal`):** polling em `/api/resources/account` com intervalos curtos (20 ms) até que a resposta passe de 200 para 401. Verifica que a expiração residual do token é maior que 5 s para garantir que a recusa decorre da revogação e não de expiração natural. Registra o tempo de propagação como duração da amostra.

O runner calcula percentis e janelas para a chamada de revogação e consolida o bloco `propagation` com `observations`, `failures` e percentis `p50Ms`, `p95Ms`, `p99Ms`, confrontando com a meta `targetPropagationMs = 2000`.

## Validação local

Comando executado:
```powershell
.\mvnw.cmd -B -ntp -pl integration-tests -am '-Dtest=NoSelectedUnitTests' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=TokenBaselineSmoke' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djacoco.skip=true' '-Dsecuregate.jmeter.home=C:/Projetos/SecureGate IAM/.firecrawl/tools/apache-jmeter-5.6.3' '-Dsecuregate.load.scenario=revocation' verify
```

Resultado: **1 teste executado, 0 falhas, 0 erros, 0 ignorados; BUILD SUCCESS** em 1 min 23 s.
A validação cobriu as três fases (warmup 8 s, steady 10 s, cooldown 8 s), gerando chamadas de revogação bem-sucedidas, observações de recusa com HTTP 401 no recurso dentro do prazo, janelas completas, métricas dos 4 containers de dependência (PostgreSQL, Redis, RabbitMQ e Vault) e processo IAM, e ausência de segredos ou tokens JWT nos artefatos.

Também foi incluída a configuração `withExtraHost("host.docker.internal", "host-gateway")` no Testcontainers para compatibilidade com execução no Linux (Ubuntu/GitHub Actions).

## Limites

A medição em ambiente local isolado reflete a infraestrutura local (Docker Desktop / loopback). A separação garante que o tempo de polling não contamina a taxa de chamadas do endpoint `/oauth2/revoke`. O p95 de propagação medido atende à meta de < 2000 ms no ambiente executado. A próxima divisão **10C2b2b3** abordará a medição de auditoria centralizada. [Operação](../runbooks/medicao-revogacao-local.md).

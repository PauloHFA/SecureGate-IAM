# Evidências — Etapa 12A: API Gateway e Roteamento de Borda com Validação Redis

- **Data e responsável:** 23/10/2026, Engenharia SecureGate IAM
- **Branch:** `feat/api-gateway-route-policies`
- **Ambiente:** Windows, Java 21 OpenJDK, Maven 3.9+, GitHub Actions
- **ADR relacionada:** [ADR-071](../adr/071-api-gateway-e-politicas-por-rota.md)
- **Runbook operacional:** [Runbook do API Gateway](../runbooks/api-gateway-local.md)

---

## 1. Escopo e Entregas da Etapa 12A

A entrega **12A** introduz a camada de borda do ecossistema SecureGate IAM:

1. **Módulo Reativo `api-gateway` ([`api-gateway/pom.xml`](../../api-gateway/pom.xml)):**
   - Implementação reativa baseada em **Spring Cloud Gateway** e Netty.
   - Integração com `spring-boot-starter-data-redis-reactive` e `com.securegate:security-events`.

2. **Roteamento Inteligente ([`application.yml`](../../api-gateway/src/main/resources/application.yml)):**
   - Rota de autenticação pública: `/.well-known/**`, `/oauth2/**`, `/login/**`, `/mfa/**`.
   - Rota de recursos protegidos: `/api/resources/**`, `/api/pam/**`.
   - Rota administrativa: `/api/admin/**`.

3. **Filtro de Revogação de Borda e Fail-Closed ([`TokenRevocationGatewayFilter.java`](../../api-gateway/src/main/java/com/securegate/gateway/TokenRevocationGatewayFilter.java)):**
   - Extração não bloqueante de claims JWT (`jti`, `sid`, `sub`).
   - Consulta atômica das projeções Redis geradas pelo Policy Enforcer via `SecurityProjectionKeys`.
   - Descarte precoce com HTTP 401 para tokens revogados ou contas bloqueadas.
   - Postura *Fail-Closed*: erro de conectividade com Redis resulta em HTTP 503 com Problem JSON (RFC 7807), impedindo bypass.

4. **Empacotamento e Compose:**
   - Dockerfile multi-stage Java 21 non-root ([`api-gateway/Dockerfile`](../../api-gateway/Dockerfile)).
   - Inclusão do serviço `api-gateway` nos perfis `gateway` e `full` no [`infra/docker-compose.yml`](../../infra/docker-compose.yml).

5. **Validação Automatizada de Contratos:**
   - 6 testes unitários no módulo gateway ([`TokenRevocationGatewayFilterTest.java`](../../api-gateway/src/test/java/com/securegate/gateway/TokenRevocationGatewayFilterTest.java)).
   - 4 testes de integração de governança ([`ApiGatewayIT.java`](../../integration-tests/src/test/java/com/securegate/e2e/ApiGatewayIT.java)).

---

## 2. Validação Local

Comando executado:
```powershell
.\mvnw.cmd -B -ntp -pl :api-gateway,:integration-tests -am '-Dtest=TokenRevocationGatewayFilterTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=ApiGatewayIT,CleanInstallReleaseIT,SecurityHardeningIT' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djacoco.skip=true' verify
```

Resultado observado:
```text
[INFO] Running com.securegate.gateway.TokenRevocationGatewayFilterTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 2.809 s -- in com.securegate.gateway.TokenRevocationGatewayFilterTest
[INFO] Running com.securegate.e2e.ApiGatewayIT
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.082 s -- in com.securegate.e2e.ApiGatewayIT
[INFO] Running com.securegate.e2e.CleanInstallReleaseIT
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.068 s -- in com.securegate.e2e.CleanInstallReleaseIT
[INFO] Running com.securegate.e2e.SecurityHardeningIT
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.079 s -- in com.securegate.e2e.SecurityHardeningIT
[INFO] Results:
[INFO] Tests run: 19, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

- Testes executados: **19 aprovados com 0 falhas, 0 erros, 0 skips**.
- Integridade de formato: **Spotless 100% limpo**.

---

## 3. Próximos Passos

A entrega **12A** estabelece o API Gateway operacional com roteamento e filtragem de revogação. A próxima subdivisão é a **12B (Jornada Demonstrável Ponta a Ponta)**, reproduzindo o fluxo interativo completo de emissão de token, acesso a recurso protegido via Gateway, disparo de evento suspeito, bloqueio em tempo real pelo Policy Enforcer e recusa imediata na borda.


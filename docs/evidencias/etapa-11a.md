# Evidências — Etapa 11A: Imagens Multi-stage e Perfil Compose Full

- **Data e responsável:** 23/10/2026, Engenharia SecureGate IAM
- **Branch:** `feat/container-images-full-compose`
- **Ambiente:** Windows, Java 21 OpenJDK, Maven 3.9+, Docker Compose v2
- **ADR relacionada:** [ADR-067](../adr/067-imagens-multi-stage-e-perfil-full.md)
- **Runbook operacional:** [Runbook da Stack Completa](../runbooks/stack-completa-local.md)

---

## 1. Escopo e Entregas da Etapa 11A

A entrega **11A** dá início à fase de empacotamento, entrega e hardening para a versão `v1.0.0`:
1. **Dockerfiles Multi-stage Java 21:**
   - [`iam-core/Dockerfile`](../../iam-core/Dockerfile): estágio builder com `eclipse-temurin:21-jdk-alpine` e runtime com `eclipse-temurin:21-jre-alpine`. Execução sob usuário sem privilégios (`securegate`, UID/GID `10001`). Healthcheck nativo com consulta ao `/actuator/health/readiness`.
   - [`policy-enforcer/Dockerfile`](../../policy-enforcer/Dockerfile): estágio builder e runtime non-root minimalista em Java 21.
   - [`.dockerignore`](../../.dockerignore): isolamento estrito contra arquivos locais, git, logs e credenciais de desenvolvimento.
2. **Orquestração Declarativa Completa ([`infra/docker-compose.yml`](../../infra/docker-compose.yml)):**
   - Configuração do perfil `full` agregando os serviços `iam-core` e `policy-enforcer` à topologia de infraestrutura existente (`postgres`, `redis`, `rabbitmq`, `vault`, `vault-init`).
   - Dependências de saúde (`depends_on`) assegurando que a aplicação só inicie quando todas as dependências estiverem `healthy` e o bootstrap `vault-init` concluído com sucesso.
   - Montagem de segredos e credenciais de AppRole por arquivos somente-leitura em `/etc/securegate/`.
3. **Smoke Test da Stack ([`infra/scripts/Test-FullStack.ps1`](../../infra/scripts/Test-FullStack.ps1)):**
   - Script automatizado de validação de saúde, liveness, readiness, discovery OIDC (`/.well-known/openid-configuration`), chaves públicas JWKS (`/oauth2/jwks`) e contrato negativo do endpoint `/oauth2/token`.
4. **Validação Automatizada de Contratos ([`FullStackSmokeIT.java`](../../integration-tests/src/test/java/com/securegate/e2e/FullStackSmokeIT.java)):**
   - Teste automatizado que audita os Dockerfiles (multi-stage, non-root, flags JVM `-XX:+UseContainerSupport` e `-XX:MaxRAMPercentage=75.0`, healthchecks), a especificação do `docker-compose.yml` e a sintaxe do script de smoke.

---

## 2. Validação Local

Comando executado:
```powershell
.\mvnw.cmd -B -ntp -pl :integration-tests -am '-Dtest=NoSelectedUnitTests' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=FullStackSmokeIT' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djacoco.skip=true' verify
```

Resultado observado:
```text
[INFO] Running com.securegate.e2e.FullStackSmokeIT
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.061 s -- in com.securegate.e2e.FullStackSmokeIT
[INFO] Results:
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
[INFO] Reactor Summary for SecureGate IAM 0.1.0-SNAPSHOT:
[INFO] SecureGate IAM ..................................... SUCCESS [  1.131 s]
[INFO] SecureGate Security Events ......................... SUCCESS [  1.436 s]
[INFO] SecureGate IAM Core ................................ SUCCESS [  3.529 s]
[INFO] SecureGate Policy Enforcer ......................... SUCCESS [  0.437 s]
[INFO] SecureGate End-to-end Tests ........................ SUCCESS [  5.290 s]
[INFO] BUILD SUCCESS in 12.294 s
```

- Testes executados: **3 aprovados com 0 falhas, 0 erros, 0 skips**.
- Integridade de formato: **Spotless 100% limpo**.

---

## 3. Próximos Passos

A entrega **11A** atende ao critério de imagens de container e stack executável via perfil `full`. A próxima subdivisão é a **11B (CI e release)**, integrando análise estática CodeQL, scans de vulnerabilidades de dependências/imagens e automação de publicação no GHCR no fluxo de release.

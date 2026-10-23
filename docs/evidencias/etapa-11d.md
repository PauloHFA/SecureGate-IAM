# Evidências — Etapa 11D: Fechamento, Aceite Final e Release v1.0.0

- **Data e responsável:** 23/10/2026, Engenharia SecureGate IAM
- **Branch:** `feat/release-v1.0.0-closure`
- **Ambiente:** Windows, Java 21 OpenJDK, Maven 3.9+, GitHub Actions
- **ADR relacionada:** [ADR-070](../adr/070-fechamento-aceite-final-e-release-v1.md)
- **Runbook operacional:** [Runbook de Instalação Limpa e Release v1.0.0](../runbooks/instalacao-limpa-e-release-v1.md)
- **Relatório de Aceite:** [Relatório de Aceite Técnico Final v1.0.0](../relatorios/aceite-final-v1.0.0.md)

---

## 1. Escopo e Entregas da Etapa 11D

A entrega **11D** conclui o ciclo formal das Etapas 0 a 11, consolidando a primeira versão estável de produção (**v1.0.0**) do SecureGate IAM:

1. **Procedimento e Runbook de Instalação Limpa ([`docs/runbooks/instalacao-limpa-e-release-v1.md`](../runbooks/instalacao-limpa-e-release-v1.md)):**
   - Especificação de pré-requisitos mínimos de host (Docker 24+, 4 GB RAM, 2 CPUs).
   - Sequência operacional de *cold start* via `docker compose -f infra/docker-compose.yml --profile full up -d --build`.
   - Monitoramento do endpoint `/actuator/health/readiness` e execução do smoke test da stack.
   - Procedimentos de encerramento, limpeza de volumes e fluxo de tagueamento oficial.

2. **Relatório de Aceite Técnico Final ([`docs/relatorios/aceite-final-v1.0.0.md`](../relatorios/aceite-final-v1.0.0.md)):**
   - Formalização de conformidade de 100% dos requisitos mandatórios das Etapas 0 a 11.
   - Consolidação das métricas de desempenho de carga (emissão com semáforo Transit, introspecção com cache p95 < 20 ms, propagação de revogação < 2 s).
   - Confirmação de postura de segurança (STRIDE, OWASP ASVS Nível 2, CodeQL sem alertas, Trivy zero vulnerabilidades `CRITICAL`/`HIGH`, GitGuardian zero segredos expostos).

3. **Decisão Arquitetural ([`docs/adr/070-fechamento-aceite-final-e-release-v1.md`](../adr/070-fechamento-aceite-final-e-release-v1.md)):**
   - Registro formal de fechamento e homologação da versão `v1.0.0`.
   - Definição do desacoplamento da Etapa 12 (API Gateway opcional).

4. **Validação Automatizada de Contratos ([`CleanInstallReleaseIT.java`](../../integration-tests/src/test/java/com/securegate/e2e/CleanInstallReleaseIT.java)):**
   - 4 testes automatizados auditando requisitos de instalação limpa, relatório de aceite, ADR-070 e alinhamento do workflow de release `.github/workflows/release.yml` para tags `v*`.

---

## 2. Validação Local

Comando executado:
```powershell
.\mvnw.cmd -B -ntp -pl :integration-tests -am '-Dtest=NoSelectedUnitTests' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=CleanInstallReleaseIT,SecurityHardeningIT,CiReleasePipelineIT,FullStackSmokeIT' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djacoco.skip=true' verify
```

Resultado observado:
```text
[INFO] Running com.securegate.e2e.CleanInstallReleaseIT
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.070 s -- in com.securegate.e2e.CleanInstallReleaseIT
[INFO] Running com.securegate.e2e.SecurityHardeningIT
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.081 s -- in com.securegate.e2e.SecurityHardeningIT
[INFO] Running com.securegate.e2e.CiReleasePipelineIT
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.077 s -- in com.securegate.e2e.CiReleasePipelineIT
[INFO] Running com.securegate.e2e.FullStackSmokeIT
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.015 s -- in com.securegate.e2e.FullStackSmokeIT
[INFO] Results:
[INFO] Tests run: 16, Failures: 0, Errors: 0, Skipped: 0
[INFO] Reactor Summary for SecureGate IAM 0.1.0-SNAPSHOT:
[INFO] SecureGate IAM ..................................... SUCCESS [  1.138 s]
[INFO] SecureGate Security Events ......................... SUCCESS [  1.281 s]
[INFO] SecureGate IAM Core ................................ SUCCESS [  2.232 s]
[INFO] SecureGate Policy Enforcer ......................... SUCCESS [  0.441 s]
[INFO] SecureGate End-to-end Tests ........................ SUCCESS [  5.163 s]
[INFO] BUILD SUCCESS in 10.716 s
```

- Testes executados: **16 aprovados com 0 falhas, 0 erros, 0 skips**.
- Integridade de formato: **Spotless 100% limpo**.

---

## 3. Próximos Passos

A entrega **11D** atende plenamente ao encerramento do marco `v1.0.0`. Após o merge desta entrega e a publicação da tag `v1.0.0`, o roadmap avançará para a **Etapa 12 (Gateway e Demonstração Opcional — 12A, 12B, 12C)**.

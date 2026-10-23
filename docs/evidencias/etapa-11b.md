# Evidências — Etapa 11B: CI e Release — CodeQL, Scans e Publicação GHCR

- **Data e responsável:** 23/10/2026, Engenharia SecureGate IAM
- **Branch:** `feat/ci-codeql-ghcr-release`
- **Ambiente:** Windows, Java 21 OpenJDK, Maven 3.9+, GitHub Actions
- **ADR relacionada:** [ADR-068](../adr/068-ci-codeql-e-publicacao-ghcr.md)
- **Runbook operacional:** [Runbook de CI/CD e Publicação GHCR](../runbooks/ci-cd-e-publicacao-ghcr.md)

---

## 1. Escopo e Entregas da Etapa 11B

A entrega **11B** estabelece a esteira de integridade contínua, governança estática e publicação de artefatos de container:
1. **SAST com GitHub CodeQL ([`.github/workflows/codeql.yml`](../../.github/workflows/codeql.yml)):**
   - Análise estática avançada cobrindo código Java e Kotlin.
   - Conjuntos de regras estendidas: `security-extended` e `security-and-quality`.
   - Execução em disparos na branch principal, pull requests e varredura programada semanal (cron).
2. **Scan de Vulnerabilidades de Containers no CI ([`.github/workflows/ci.yml`](../../.github/workflows/ci.yml)):**
   - Inclusão do job `container-build-scan` executado após o build e validação Maven.
   - Build local das imagens Docker `iam-core:ci-scan` e `policy-enforcer:ci-scan`.
   - Varredura de vulnerabilidades de dependências e SO de base com Aquasecurity Trivy (`aquasecurity/trivy-action`).
   - Política estrita de severidade: quebra de pipeline em severidades `CRITICAL` e `HIGH` com tabela SARIF.
3. **Publicação no GitHub Packages / GHCR ([`.github/workflows/release.yml`](../../.github/workflows/release.yml)):**
   - Workflow dedicado para build, tagueamento e publicação das imagens no GitHub Container Registry (`ghcr.io`).
   - Disparo restrito a tags de versão (`v*`, como `v1.0.0`) e acionamento manual (`workflow_dispatch`).
   - Isolamento de fronteira: proibição estrita de publicação via `pull_request` para evitar poluição do repositório de artefatos.
   - Suporte a multi-arquitetura (`linux/amd64`, `linux/arm64`) via QEMU e Buildx.
4. **Governança Automatizada de Pipelines ([`CiReleasePipelineIT.java`](../../integration-tests/src/test/java/com/securegate/e2e/CiReleasePipelineIT.java)):**
   - 4 testes automatizados auditando:
     - Configuração e queries do CodeQL.
     - Fronteiras de disparo e isolamento de PR no workflow de release.
     - Padrão de segurança de supply chain com pinning de actions por SHA (commit hash).
     - Configuração e critérios de quebra do scanner Trivy no CI.

---

## 2. Validação Local

Comando executado:
```powershell
.\mvnw.cmd -B -ntp -pl :integration-tests -am '-Dtest=NoSelectedUnitTests' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=CiReleasePipelineIT,FullStackSmokeIT' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djacoco.skip=true' verify
```

Resultado observado:
```text
[INFO] Running com.securegate.e2e.CiReleasePipelineIT
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.088 s -- in com.securegate.e2e.CiReleasePipelineIT
[INFO] Running com.securegate.e2e.FullStackSmokeIT
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.015 s -- in com.securegate.e2e.FullStackSmokeIT
[INFO] Results:
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0
[INFO] Reactor Summary for SecureGate IAM 0.1.0-SNAPSHOT:
[INFO] SecureGate IAM ..................................... SUCCESS [  0.771 s]
[INFO] SecureGate Security Events ......................... SUCCESS [  1.200 s]
[INFO] SecureGate IAM Core ................................ SUCCESS [  1.884 s]
[INFO] SecureGate Policy Enforcer ......................... SUCCESS [  0.370 s]
[INFO] SecureGate End-to-end Tests ........................ SUCCESS [  2.051 s]
[INFO] BUILD SUCCESS in 6.332 s
```

- Testes executados: **7 aprovados com 0 falhas, 0 erros, 0 skips**.
- Integridade de formato: **Spotless 100% limpo**.

---

## 3. Próximos Passos

A entrega **11B** conclui a esteira de SAST, scans de vulnerabilidades e automação de release GHCR. A próxima subdivisão é a **11C (Hardening e postura de segurança)**, consolidando TLS, CORS, headers de segurança, rate limiting, Threat Model formal e matriz de controles OWASP ASVS.

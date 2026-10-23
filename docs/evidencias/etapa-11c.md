# Evidências — Etapa 11C: Hardening e Postura de Segurança

- **Data e responsável:** 23/10/2026, Engenharia SecureGate IAM
- **Branch:** `feat/security-hardening-asvs`
- **Ambiente:** Windows, Java 21 OpenJDK, Maven 3.9+, GitHub Actions
- **ADR relacionada:** [ADR-069](../adr/069-hardening-postura-de-seguranca-e-asvs.md)
- **Runbooks operacionais:**
  - [Runbook de Hardening e Postura de Segurança](../runbooks/hardening-e-postura-de-seguranca.md)
  - [Runbook de Proteção de Branch e Governança](../runbooks/protecao-branch-main.md)
- **Documentos de Segurança:**
  - [Modelo de Ameaças STRIDE](../seguranca/threat-model.md)
  - [Matriz de Conformidade OWASP ASVS v4.0.3](../seguranca/owasp-asvs-revisao.md)

---

## 1. Escopo e Entregas da Etapa 11C

A entrega **11C** consolida a postura de segurança em camadas e governança formal da solução para a versão `v1.0.0`:

1. **Modelo de Ameaças STRIDE ([`docs/seguranca/threat-model.md`](../seguranca/threat-model.md)):**
   - Análise aprofundada cobrindo os 6 vetores STRIDE (*Spoofing, Tampering, Repudiation, Information Disclosure, Denial of Service, Elevation of Privilege*).
   - Definição formal das 5 fronteiras de confiança (*Trust Boundaries*), inventário de ativos críticos, mapeamento de mitigações de código e matriz de risco residual.

2. **Matriz de Revisão OWASP ASVS v4.0.3 Nível 2 ([`docs/seguranca/owasp-asvs-revisao.md`](../seguranca/owasp-asvs-revisao.md)):**
   - Mapeamento ponto a ponto dos capítulos críticos V1 a V14 do padrão ASVS.
   - Demonstração da aplicação de *Security by Design*: senhas com Argon2id ($m=19456 \text{ KB}, t=2, p=1$), TOTP com chaves protegidas por AES-256-GCM no Vault Transit, JWT RS256 de curta duração (5 min), rotação compulsória de refresh tokens, *Deny-by-default*, *Deny-overrides* no ABAC/RBAC e RFC 7807 (Problem Details).

3. **Diretrizes de Hardening e Cabeçalhos de Segurança ([`docs/runbooks/hardening-e-postura-de-seguranca.md`](../runbooks/hardening-e-postura-de-seguranca.md)):**
   - Especificação de terminação TLS 1.2/1.3 na borda com cifras seguras e HSTS.
   - Catálogo de cabeçalhos de segurança: `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`, `Content-Security-Policy (CSP)`, `Permissions-Policy` e `Cache-Control: no-store`.
   - Limites de conexões, timeouts Tomcat e controle de concorrência com semáforo no Vault Transit para mitigação de DoS.

4. **Governança da Branch `main` ([`docs/runbooks/protecao-branch-main.md`](../runbooks/protecao-branch-main.md)):**
   - Formalização de regras de proteção de branch exigindo 4 status checks obrigatórios (`Maven verify`, `CodeQL`, `Trivy scan`, `GitGuardian`).
   - Política de branches atualizadas, linearidade do histórico e proibição estrita de *force push* e deleção da `main`.

5. **Validação Automatizada de Contratos de Segurança ([`SecurityHardeningIT.java`](../../integration-tests/src/test/java/com/securegate/e2e/SecurityHardeningIT.java)):**
   - 5 testes automatizados auditando integridade e completude de Threat Model, ASVS, runbooks, e configurações de Spring Security (`SessionCreationPolicy.STATELESS`, `denyAll()`, Argon2id, `no-referrer`, `ProblemResponses`).

---

## 2. Validação Local

Comando executado:
```powershell
.\mvnw.cmd -B -ntp -pl :integration-tests -am '-Dtest=NoSelectedUnitTests' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=SecurityHardeningIT,CiReleasePipelineIT,FullStackSmokeIT' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djacoco.skip=true' verify
```

Resultado observado:
```text
[INFO] Running com.securegate.e2e.SecurityHardeningIT
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.080 s -- in com.securegate.e2e.SecurityHardeningIT
[INFO] Running com.securegate.e2e.CiReleasePipelineIT
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.076 s -- in com.securegate.e2e.CiReleasePipelineIT
[INFO] Running com.securegate.e2e.FullStackSmokeIT
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.015 s -- in com.securegate.e2e.FullStackSmokeIT
[INFO] Results:
[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0
[INFO] Reactor Summary for SecureGate IAM 0.1.0-SNAPSHOT:
[INFO] SecureGate IAM ..................................... SUCCESS [  0.946 s]
[INFO] SecureGate Security Events ......................... SUCCESS [  1.182 s]
[INFO] SecureGate IAM Core ................................ SUCCESS [  1.732 s]
[INFO] SecureGate Policy Enforcer ......................... SUCCESS [  0.346 s]
[INFO] SecureGate End-to-end Tests ........................ SUCCESS [  4.455 s]
[INFO] BUILD SUCCESS in 9.282 s
```

- Testes executados: **12 aprovados com 0 falhas, 0 erros, 0 skips**.
- Integridade de formato: **Spotless 100% limpo**.

---

## 3. Próximos Passos

A entrega **11C** conclui a formalização de hardening, Threat Model STRIDE, matriz de conformidade OWASP ASVS e governança de proteção de branch. A próxima subdivisão é a **11D (Fechamento e Release v1.0.0)**, reproduzindo a validação de instalação limpa, conferindo integridade total de documentação e preparando o tagueamento oficial `v1.0.0`.

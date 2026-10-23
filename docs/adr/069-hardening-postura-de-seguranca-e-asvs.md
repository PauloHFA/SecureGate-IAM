# ADR-069: Hardening, Postura de Segurança e Conformidade OWASP ASVS

- **Status:** Aceito
- **Data:** 23/10/2026
- **Contexto:** Etapa 11C — Entrega e Hardening
- **Decisores:** Engenharia SecureGate IAM

---

## 1. Contexto e Problema

Para a consolidação da versão estável `v1.0.0` do SecureGate IAM, é imperativo formalizar e verificar a postura de segurança em camadas da solução, mitigando riscos de exploração web, vazamento de credenciais, abuso de conexões e adulteração de código. Era necessário estabelecer:
1. Uma modelagem formal de ameaças estruturada pelo método STRIDE.
2. Uma matriz de revisão e aderência aos requisitos do **OWASP Application Security Verification Standard (ASVS) v4.0.3** no Nível 2.
3. Especificações técnicas consolidadas para terminação TLS na borda, cabeçalhos de segurança HTTP (CSP, nosniff, DENY, no-referrer), controle de CORS e proteção contra DoS.
4. Políticas estritas de governança da branch `main`, exigindo status checks automatizados e proibição de commits diretos.

---

## 2. Decisão Arquitetural

Decidiu-se pela formalização e implementação dos seguintes padrões arquiteturais de segurança:

1. **Modelo de Ameaças STRIDE:**
   - Documentado em [`docs/seguranca/threat-model.md`](../seguranca/threat-model.md), cobrindo as 5 fronteiras de confiança do ecossistema e mitigando formalmente cada uma das categorias STRIDE.

2. **Matriz de Conformidade OWASP ASVS v4.0.3 Nível 2:**
   - Formalizada em [`docs/seguranca/owasp-asvs-revisao.md`](../seguranca/owasp-asvs-revisao.md), mapeando os capítulos V1 a V14 aos controles de código implementados (Argon2id, TOTP com AES-256-GCM, JWT RS256 no Vault Transit, Deny-by-default, prepared statements, etc.).

3. **Catálogo Mandatório de Cabeçalhos de Segurança:**
   - Exigência de injeção dos headers `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer` e `Cache-Control: no-store` em todas as rotas de autenticação, recursos protegidos e administração.
   - Respostas de erro estritamente em conformidade com RFC 7807 (Problem Details), sem vazamento de stack traces ou exceções internas.

4. **Regras de Proteção de Branch e Integridade de Supply Chain:**
   - Documentadas no runbook [`docs/runbooks/protecao-branch-main.md`](../runbooks/protecao-branch-main.md), consolidando os 4 status checks obrigatórios (`Maven verify`, `CodeQL`, `Trivy scan` e `GitGuardian`), pinning de actions por SHA-1 e vedação de merges sem PR aprovado.

---

## 3. Consequências

### Positivas:
- **Resiliência Reforçada:** Aplicação protegida contra ataques comuns de injeção, framing, timing attacks e abuso de concorrência.
- **Rastreabilidade e Não-Repúdio:** Modelo formal de ameaças e trilha de auditoria transacional garantem conformidade com normas regulatórias corporativas.
- **Supply Chain Blindado:** Ações fixadas por SHA e scans automáticos de vulnerabilidade impedem contaminação de dependências ou imagens base.

### Neutras / Compensações:
- Exigência de proxy reverso com terminação TLS em ambientes produtivos para injeção de HSTS e gestão de certificados HTTPS.
- Overhead adicional de governança que requer aprovação de PR e execução completa da suíte de CI para qualquer integração na `main`.

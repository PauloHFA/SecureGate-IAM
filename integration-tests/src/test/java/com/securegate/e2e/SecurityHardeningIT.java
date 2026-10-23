package com.securegate.e2e;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Integration test for Stage 11C: validates security hardening, STRIDE threat modeling, OWASP ASVS
 * compliance specifications, security headers, and main branch protection governance.
 */
class SecurityHardeningIT {

  @Test
  void validatesThreatModelCompletenessAndStrideCoverage() throws Exception {
    Path tmPath = Path.of("../docs/seguranca/threat-model.md").toAbsolutePath();
    assertTrue(Files.isRegularFile(tmPath), "threat-model.md must exist in docs/seguranca/");

    String content = Files.readString(tmPath);
    assertTrue(content.contains("STRIDE"), "Must follow STRIDE methodology");
    assertTrue(content.contains("Spoofing"), "Must analyze Spoofing threats");
    assertTrue(content.contains("Tampering"), "Must analyze Tampering threats");
    assertTrue(content.contains("Repudiation"), "Must analyze Repudiation threats");
    assertTrue(content.contains("Information Disclosure"), "Must analyze Information Disclosure");
    assertTrue(content.contains("Denial of Service"), "Must analyze Denial of Service");
    assertTrue(content.contains("Elevation of Privilege"), "Must analyze Elevation of Privilege");
    assertTrue(content.contains("Fronteiras de Confiança"), "Must document trust boundaries");
    assertTrue(content.contains("Argon2id"), "Must reference Argon2id password hashing");
    assertTrue(
        content.contains("Vault Transit"), "Must reference Vault Transit cryptographic engine");
  }

  @Test
  void validatesOwaspAsvsReviewMatrixCompleteness() throws Exception {
    Path asvsPath = Path.of("../docs/seguranca/owasp-asvs-revisao.md").toAbsolutePath();
    assertTrue(
        Files.isRegularFile(asvsPath), "owasp-asvs-revisao.md must exist in docs/seguranca/");

    String content = Files.readString(asvsPath);
    assertTrue(content.contains("OWASP ASVS v4.0.3"), "Must reference OWASP ASVS v4.0.3");
    assertTrue(content.contains("V1: Arquitetura"), "Must cover V1 Architecture");
    assertTrue(content.contains("V2: Autenticação"), "Must cover V2 Authentication");
    assertTrue(content.contains("V3: Gerenciamento de Sessão"), "Must cover V3 Session Management");
    assertTrue(content.contains("V4: Controle de Acesso"), "Must cover V4 Access Control");
    assertTrue(content.contains("V5: Validação"), "Must cover V5 Validation");
    assertTrue(content.contains("V6: Criptografia"), "Must cover V6 Cryptography");
    assertTrue(content.contains("V7: Tratamento de Erros"), "Must cover V7 Error Handling");
    assertTrue(content.contains("V8: Proteção de Dados"), "Must cover V8 Data Protection");
    assertTrue(content.contains("V9: Comunicações"), "Must cover V9 Communications");
    assertTrue(content.contains("V10: Código Malicioso"), "Must cover V10 Malicious Code");
    assertTrue(content.contains("V13: APIs"), "Must cover V13 APIs");
    assertTrue(content.contains("V14: Configuração"), "Must cover V14 Configuration");
  }

  @Test
  void validatesHardeningRunbookAndSecurityHeadersDirectives() throws Exception {
    Path runbookPath =
        Path.of("../docs/runbooks/hardening-e-postura-de-seguranca.md").toAbsolutePath();
    assertTrue(Files.isRegularFile(runbookPath), "Hardening runbook must exist");

    String content = Files.readString(runbookPath);
    assertTrue(content.contains("TLS 1.2"), "Must specify TLS 1.2 minimum");
    assertTrue(content.contains("TLS 1.3"), "Must specify TLS 1.3");
    assertTrue(content.contains("X-Content-Type-Options"), "Must document nosniff header");
    assertTrue(content.contains("X-Frame-Options"), "Must document DENY frame header");
    assertTrue(content.contains("Referrer-Policy"), "Must document no-referrer header");
    assertTrue(content.contains("Content-Security-Policy"), "Must document CSP");
    assertTrue(content.contains("Strict-Transport-Security"), "Must document HSTS header");
    assertTrue(content.contains("DistributedLoginFilter"), "Must document brute force rate limit");
  }

  @Test
  void validatesMainBranchProtectionRunbookRequirements() throws Exception {
    Path protectionPath = Path.of("../docs/runbooks/protecao-branch-main.md").toAbsolutePath();
    assertTrue(Files.isRegularFile(protectionPath), "Branch protection runbook must exist");

    String content = Files.readString(protectionPath);
    assertTrue(content.contains("Require a pull request"), "Must require PRs");
    assertTrue(content.contains("Java 21 / Maven verify"), "Must require Maven verify check");
    assertTrue(content.contains("CodeQL Analyze"), "Must require CodeQL check");
    assertTrue(
        content.contains("Container Build & Vulnerability Scan"), "Must require Trivy check");
    assertTrue(content.contains("GitGuardian Security Checks"), "Must require GitGuardian check");
    assertTrue(content.contains("Do not allow force pushes"), "Must prohibit force pushes");
  }

  @Test
  void validatesCodeHardeningStandardsInSpringSecurityConfigurations() throws Exception {
    Path secConfig =
        Path.of("../iam-core/src/main/java/com/securegate/iam/bootstrap/SecurityConfiguration.java")
            .toAbsolutePath();
    assertTrue(Files.isRegularFile(secConfig), "SecurityConfiguration.java must exist");
    String secContent = Files.readString(secConfig);
    assertTrue(
        secContent.contains("SessionCreationPolicy.STATELESS"), "Must enforce stateless sessions");
    assertTrue(secContent.contains("anyRequest()"), "Must define anyRequest rule");
    assertTrue(secContent.contains("denyAll()"), "Must enforce deny-by-default");

    Path browserConfig =
        Path.of(
                "../iam-core/src/main/java/com/securegate/iam/bootstrap/BrowserSecurityConfiguration.java")
            .toAbsolutePath();
    assertTrue(Files.isRegularFile(browserConfig), "BrowserSecurityConfiguration.java must exist");
    String browserContent = Files.readString(browserConfig);
    assertTrue(
        browserContent.contains("Argon2PasswordEncoder(16, 32, 1, 19456, 2)"),
        "Must use Argon2id with 19456 KB memory and 2 iterations");
    assertTrue(
        browserContent.contains("NO_REFERRER"), "Must enforce NO_REFERRER policy in browser");

    Path problemResponses =
        Path.of(
                "../iam-core/src/main/java/com/securegate/iam/shared/presentation/ProblemResponses.java")
            .toAbsolutePath();
    assertTrue(Files.isRegularFile(problemResponses), "ProblemResponses.java must exist");
    String problemsContent = Files.readString(problemResponses);
    assertTrue(
        problemsContent.contains("application/problem+json")
            || problemsContent.contains("APPLICATION_PROBLEM_JSON_VALUE"),
        "Must emit RFC 7807 problem details");
  }
}

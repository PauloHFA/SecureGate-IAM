package com.securegate.e2e;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Integration test for Stage 11D: validates clean environment installation procedures, final
 * technical acceptance report for v1.0.0, release tagging specifications, and ADR-070.
 */
class CleanInstallReleaseIT {

  @Test
  void validatesCleanInstallRunbookCompletenessAndColdStartProcedures() throws Exception {
    Path runbookPath =
        Path.of("../docs/runbooks/instalacao-limpa-e-release-v1.md").toAbsolutePath();
    assertTrue(Files.isRegularFile(runbookPath), "Clean install runbook must exist");

    String content = Files.readString(runbookPath);
    assertTrue(content.contains("Docker Engine"), "Must document Docker requirements");
    assertTrue(content.contains("4 GB"), "Must specify minimum memory requirements");
    assertTrue(content.contains("--profile full"), "Must instruct starting with full profile");
    assertTrue(
        content.contains("/actuator/health/readiness"), "Must specify readiness check endpoint");
    assertTrue(content.contains("Test-FullStack.ps1"), "Must instruct running smoke test");
    assertTrue(content.contains("down -v"), "Must provide clean teardown instructions");
    assertTrue(content.contains("git tag -a v1.0.0"), "Must document release tagging command");
    assertTrue(content.contains("git push origin v1.0.0"), "Must document release push command");
  }

  @Test
  void validatesFinalAcceptanceReportForVersion1ReleaseSignOff() throws Exception {
    Path reportPath = Path.of("../docs/relatorios/aceite-final-v1.0.0.md").toAbsolutePath();
    assertTrue(Files.isRegularFile(reportPath), "Final acceptance report must exist");

    String content = Files.readString(reportPath);
    assertTrue(
        content.contains("APROVADO / ACEITO PARA RELEASE v1.0.0"),
        "Must have official approval status");
    assertTrue(content.contains("1.0.0"), "Must specify version 1.0.0");
    assertTrue(content.contains("FullStackSmokeIT"), "Must reference FullStackSmokeIT");
    assertTrue(content.contains("CiReleasePipelineIT"), "Must reference CiReleasePipelineIT");
    assertTrue(content.contains("SecurityHardeningIT"), "Must reference SecurityHardeningIT");
    assertTrue(content.contains("OWASP ASVS v4.0.3"), "Must reference OWASP ASVS compliance");
    assertTrue(content.contains("STRIDE"), "Must reference STRIDE Threat Model");
  }

  @Test
  void validatesAdr070FinalAcceptanceDecisionRecord() throws Exception {
    Path adrPath =
        Path.of("../docs/adr/070-fechamento-aceite-final-e-release-v1.md").toAbsolutePath();
    assertTrue(Files.isRegularFile(adrPath), "ADR-070 must exist");

    String content = Files.readString(adrPath);
    assertTrue(content.contains("Aceito"), "ADR-070 status must be Accepted");
    assertTrue(content.contains("v1.0.0"), "Must reference release v1.0.0");
    assertTrue(content.contains("release.yml"), "Must reference release workflow");
    assertTrue(content.contains("Etapa 12"), "Must document relationship with Stage 12");
  }

  @Test
  void validatesReleaseWorkflowAlignsWithVersion1TaggingScheme() throws Exception {
    Path releaseWorkflow = Path.of("../.github/workflows/release.yml").toAbsolutePath();
    assertTrue(Files.isRegularFile(releaseWorkflow), "release.yml must exist");

    String workflow = Files.readString(releaseWorkflow);
    assertTrue(workflow.contains("tags:"), "Workflow must trigger on git tags");
    assertTrue(workflow.contains("v*"), "Workflow must match version tag glob v*");
    assertTrue(workflow.contains("ghcr.io"), "Must publish to GHCR");
    assertTrue(workflow.contains("securegate-iam-core"), "Must name iam-core image");
    assertTrue(workflow.contains("securegate-policy-enforcer"), "Must name policy-enforcer image");
  }
}

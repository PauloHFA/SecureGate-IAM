package com.securegate.e2e;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Integration test for Stage 11B: validates CI pipeline, CodeQL security analysis, vulnerability
 * scanning, and GHCR container release specifications.
 */
class CiReleasePipelineIT {

  @Test
  void validatesCiWorkflowIncludesBuildTestAndVulnerabilityScanning() throws Exception {
    Path ciPath = Path.of("../.github/workflows/ci.yml").toAbsolutePath();
    assertTrue(Files.isRegularFile(ciPath), "ci.yml must exist");

    String ci = Files.readString(ciPath);
    assertTrue(ci.contains("name: Java 21 / Maven verify"), "Must define Java 21 verify job");
    assertTrue(ci.contains("container-build-scan:"), "Must define container-build-scan job");
    assertTrue(ci.contains("aquasecurity/trivy-action"), "Must use Trivy vulnerability scanner");
    assertTrue(ci.contains("CRITICAL,HIGH"), "Must scan for HIGH and CRITICAL vulnerabilities");
    assertTrue(ci.contains("iam-core/Dockerfile"), "Must build iam-core in CI");
    assertTrue(ci.contains("policy-enforcer/Dockerfile"), "Must build policy-enforcer in CI");
    assertTrue(ci.contains("api-gateway/Dockerfile"), "Must build api-gateway in CI");
  }

  @Test
  void validatesCodeQlWorkflowSecurityAnalysisSpecification() throws Exception {
    Path codeqlPath = Path.of("../.github/workflows/codeql.yml").toAbsolutePath();
    assertTrue(Files.isRegularFile(codeqlPath), "codeql.yml must exist");

    String codeql = Files.readString(codeqlPath);
    assertTrue(
        codeql.contains("security-events: write"), "Must have security-events write permission");
    assertTrue(codeql.contains("github/codeql-action/init"), "Must initialize CodeQL action");
    assertTrue(codeql.contains("github/codeql-action/analyze"), "Must run CodeQL analyze step");
    assertTrue(codeql.contains("java-kotlin"), "Must analyze Java/Kotlin language");
    assertTrue(
        codeql.contains("security-extended,security-and-quality"),
        "Must enable extended security queries");
  }

  @Test
  void validatesReleaseWorkflowPublishesToGhcrOnlyOnReleaseFlow() throws Exception {
    Path releasePath = Path.of("../.github/workflows/release.yml").toAbsolutePath();
    assertTrue(Files.isRegularFile(releasePath), "release.yml must exist");

    String release = Files.readString(releasePath);
    // Crucial requirement: NEVER publish PR images as official releases
    assertFalse(
        release.contains("pull_request:"), "Release workflow must NEVER trigger on pull_request");
    assertTrue(release.contains("tags:"), "Must trigger on tags");
    assertTrue(release.contains("v*"), "Must match version tags v*");
    assertTrue(release.contains("packages: write"), "Must have packages write permission for GHCR");
    assertTrue(release.contains("ghcr.io"), "Must publish to GitHub Container Registry");
    assertTrue(release.contains("securegate-iam-core"), "Must name iam-core GHCR package");
    assertTrue(release.contains("securegate-policy-enforcer"), "Must name enforcer GHCR package");
    assertTrue(release.contains("securegate-api-gateway"), "Must name gateway GHCR package");
    assertTrue(release.contains("docker/build-push-action"), "Must use build-push-action");
  }

  @Test
  void validatesSupplyChainPinningOnAllWorkflowActions() throws Exception {
    Path workflowsDir = Path.of("../.github/workflows").toAbsolutePath();
    Pattern actionUses =
        Pattern.compile("uses:\\s+([a-zA-Z0-9_.-]+(/[a-zA-Z0-9_.-]+)+)@([a-f0-9]{40}|v[0-9.]+)");

    try (var stream = Files.list(workflowsDir)) {
      for (Path workflow : stream.filter(p -> p.toString().endsWith(".yml")).toList()) {
        String content = Files.readString(workflow);
        for (String line : content.lines().toList()) {
          if (line.trim().startsWith("uses:")) {
            assertTrue(
                actionUses.matcher(line.trim()).find(),
                "Action reference must be pinned with SHA-1 commit hash in "
                    + workflow.getFileName()
                    + ": "
                    + line);
          }
        }
      }
    }
  }
}

package com.securegate.e2e;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Integration test for Stage 11A: validates container specifications, multi-stage Dockerfiles,
 * non-root user enforcement, healthcheck probes, and Compose full profile definitions.
 */
class FullStackSmokeIT {

  @Test
  void validatesDockerfilesSecurityAndMultiStageSpecifications() throws Exception {
    Path projectRoot = Path.of("..").toAbsolutePath();
    Path iamCoreDockerfile = projectRoot.resolve("iam-core/Dockerfile");
    Path enforcerDockerfile = projectRoot.resolve("policy-enforcer/Dockerfile");
    Path dockerignore = projectRoot.resolve(".dockerignore");

    assertTrue(Files.isRegularFile(iamCoreDockerfile), "iam-core/Dockerfile must exist");
    assertTrue(Files.isRegularFile(enforcerDockerfile), "policy-enforcer/Dockerfile must exist");
    assertTrue(Files.isRegularFile(dockerignore), ".dockerignore must exist");

    // Validate iam-core Dockerfile
    String iamContent = Files.readString(iamCoreDockerfile);
    assertTrue(
        iamContent.contains("FROM eclipse-temurin:21-jdk-alpine AS builder"),
        "Must use pinned JDK builder");
    assertTrue(
        iamContent.contains("FROM eclipse-temurin:21-jre-alpine AS runtime"),
        "Must use pinned JRE runtime");
    assertTrue(iamContent.contains("USER 10001:10001"), "Must run as unprivileged non-root user");
    assertTrue(iamContent.contains("HEALTHCHECK"), "Must declare Docker container healthcheck");
    assertTrue(
        iamContent.contains("/actuator/health/readiness"),
        "Healthcheck must target readiness probe");
    assertTrue(
        iamContent.contains("-XX:+UseContainerSupport"),
        "Must enable container-aware JVM ergonomics");
    assertTrue(
        iamContent.contains("-XX:MaxRAMPercentage=75.0"), "Must set bounded memory ergonomics");

    // Validate policy-enforcer Dockerfile
    String enforcerContent = Files.readString(enforcerDockerfile);
    assertTrue(
        enforcerContent.contains("FROM eclipse-temurin:21-jdk-alpine AS builder"),
        "Must use pinned JDK builder");
    assertTrue(
        enforcerContent.contains("FROM eclipse-temurin:21-jre-alpine AS runtime"),
        "Must use pinned JRE runtime");
    assertTrue(
        enforcerContent.contains("USER 10001:10001"), "Must run as unprivileged non-root user");
    assertTrue(
        enforcerContent.contains("-XX:+UseContainerSupport"), "Must enable container support");

    // Validate .dockerignore avoids sensitive leaks into build context
    String ignoreContent = Files.readString(dockerignore);
    assertTrue(ignoreContent.contains(".git"), ".dockerignore must exclude .git");
    assertTrue(
        ignoreContent.contains("infra/.local/"), ".dockerignore must exclude private credentials");
  }

  @Test
  void validatesDockerComposeFullProfileSpecification() throws Exception {
    Path composePath = Path.of("../infra/docker-compose.yml").toAbsolutePath();
    assertTrue(Files.isRegularFile(composePath), "infra/docker-compose.yml must exist");

    String compose = Files.readString(composePath);
    assertTrue(compose.contains("profiles: [full]"), "Must define full profile");
    assertTrue(compose.contains("iam-core:"), "Must configure iam-core service");
    assertTrue(compose.contains("policy-enforcer:"), "Must configure policy-enforcer service");
    assertTrue(
        compose.contains("image: securegate/iam-core:0.1.0-snapshot"), "Must use fixed image name");
    assertTrue(
        compose.contains("image: securegate/policy-enforcer:0.1.0-snapshot"),
        "Must use fixed enforcer image name");
    assertTrue(
        compose.contains("condition: service_healthy"), "Must require dependencies to be healthy");
    assertTrue(
        compose.contains("condition: service_completed_successfully"),
        "Must require vault-init to complete successfully");
  }

  @Test
  void validatesFullStackSmokeScriptExistsAndIsExecutable() throws Exception {
    Path scriptPath = Path.of("../infra/scripts/Test-FullStack.ps1").toAbsolutePath();
    assertTrue(Files.isRegularFile(scriptPath), "infra/scripts/Test-FullStack.ps1 must exist");

    String script = Files.readString(scriptPath);
    assertTrue(script.contains("/actuator/health/readiness"), "Script must test readiness probe");
    assertTrue(script.contains("/actuator/health/liveness"), "Script must test liveness probe");
    assertTrue(
        script.contains("/.well-known/openid-configuration"), "Script must test OIDC discovery");
    assertTrue(script.contains("/oauth2/jwks"), "Script must test JWKS key set");
    assertTrue(script.contains("/oauth2/token"), "Script must test token endpoint");
  }
}

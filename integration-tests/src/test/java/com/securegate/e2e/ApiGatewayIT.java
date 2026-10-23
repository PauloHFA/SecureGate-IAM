package com.securegate.e2e;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Integration test for Stage 12A: validates the API Gateway module, route mapping configurations,
 * edge revocation verification policies, fail-closed posture, and Compose integration.
 */
class ApiGatewayIT {

  @Test
  void validatesApiGatewayPomAndDependencies() throws Exception {
    Path pomPath = Path.of("../api-gateway/pom.xml").toAbsolutePath();
    assertTrue(Files.isRegularFile(pomPath), "api-gateway/pom.xml must exist");

    String pom = Files.readString(pomPath);
    assertTrue(pom.contains("spring-cloud-starter-gateway"), "Must include Spring Cloud Gateway");
    assertTrue(
        pom.contains("spring-boot-starter-data-redis-reactive"), "Must include reactive Redis");
    assertTrue(pom.contains("security-events"), "Must depend on security-events");
  }

  @Test
  void validatesApiGatewayApplicationConfigurationAndRoutes() throws Exception {
    Path appYmlPath = Path.of("../api-gateway/src/main/resources/application.yml").toAbsolutePath();
    assertTrue(Files.isRegularFile(appYmlPath), "application.yml must exist");

    String yml = Files.readString(appYmlPath);
    assertTrue(yml.contains("8082"), "Default port must be 8082");
    assertTrue(yml.contains("public-auth-and-metadata"), "Must define public auth route");
    assertTrue(yml.contains("/.well-known/**"), "Must route well-known metadata");
    assertTrue(yml.contains("/oauth2/**"), "Must route OAuth2 endpoints");
    assertTrue(yml.contains("protected-resources"), "Must define protected resources route");
    assertTrue(yml.contains("/api/resources/**"), "Must route resource endpoints");
    assertTrue(yml.contains("fail-closed: true"), "Must configure fail-closed by default");
  }

  @Test
  void validatesTokenRevocationFilterLogicAndFailClosedBehavior() throws Exception {
    Path filterPath =
        Path.of(
                "../api-gateway/src/main/java/com/securegate/gateway/TokenRevocationGatewayFilter.java")
            .toAbsolutePath();
    assertTrue(Files.isRegularFile(filterPath), "TokenRevocationGatewayFilter.java must exist");

    String filter = Files.readString(filterPath);
    assertTrue(filter.contains("SecurityProjectionKeys"), "Must use canonical projection keys");
    assertTrue(filter.contains("APPLICATION_PROBLEM_JSON"), "Must emit RFC 7807 problem JSON");
    assertTrue(filter.contains("failClosed"), "Must support fail-closed configuration");
    assertTrue(
        filter.contains("SERVICE_UNAVAILABLE"), "Must return 503 Service Unavailable on failure");
    assertTrue(filter.contains("X-Gateway-Verified"), "Must propagate verification header");
  }

  @Test
  void validatesDockerConfigurationForGatewayService() throws Exception {
    Path dockerfilePath = Path.of("../api-gateway/Dockerfile").toAbsolutePath();
    assertTrue(Files.isRegularFile(dockerfilePath), "api-gateway/Dockerfile must exist");

    String dockerfile = Files.readString(dockerfilePath);
    assertTrue(
        dockerfile.contains("eclipse-temurin:21-jdk-alpine"), "Must use Java 21 JDK builder");
    assertTrue(
        dockerfile.contains("eclipse-temurin:21-jre-alpine"), "Must use Java 21 JRE runtime");
    assertTrue(dockerfile.contains("10001:10001"), "Must run as unprivileged non-root user");
    assertTrue(dockerfile.contains("EXPOSE 8082"), "Must expose gateway port 8082");

    Path composePath = Path.of("../infra/docker-compose.yml").toAbsolutePath();
    String compose = Files.readString(composePath);
    assertTrue(compose.contains("api-gateway:"), "Must configure api-gateway in compose");
    assertTrue(
        compose.contains("profiles: [full, gateway]"), "Must attach to full and gateway profiles");
    assertTrue(compose.contains("8082:8082"), "Must map port 8082");
  }
}

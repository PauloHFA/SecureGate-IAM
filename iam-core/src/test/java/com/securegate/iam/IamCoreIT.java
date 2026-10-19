package com.securegate.iam;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({IamCoreIT.ProbeController.class, IamCoreIT.ProbeSecurity.class})
class IamCoreIT {
  @Container
  static final PostgreSQLContainer<?> DATABASE =
      new PostgreSQLContainer<>("postgres:16.15-alpine")
          .withDatabaseName("securegate_test")
          .withUsername("securegate_test")
          .withPassword(UUID.randomUUID().toString());

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
    registry.add("spring.datasource.username", DATABASE::getUsername);
    registry.add("spring.datasource.password", DATABASE::getPassword);
  }

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired Flyway flyway;
  @Autowired ObjectMapper json;
  @Autowired ApplicationContext context;
  private final HttpClient client = HttpClient.newHttpClient();

  @Test
  void startsWithPostgresAndAppliesTheVersionedMigrationOnlyOnce() {
    assertEquals(1, jdbc.queryForObject("SELECT 1", Integer.class));
    assertEquals("12", flyway.info().current().getVersion().getVersion());
    var schemas =
        jdbc.queryForList(
            "SELECT schema_name FROM information_schema.schemata WHERE schema_name LIKE 'iam_%'",
            String.class);
    assertEquals(6, schemas.size());
    assertTrue(
        schemas.containsAll(
            List.of(
                "iam_identity",
                "iam_access",
                "iam_policy",
                "iam_authorization",
                "iam_audit",
                "iam_session")));
    flyway.validate();
    assertEquals(0, flyway.migrate().migrationsExecuted);
  }

  @Test
  void disabledOAuthDoesNotCreateInMemoryClientsOrAnyLocalSigner() {
    assertEquals(
        0,
        context.getBeanNamesForType(com.securegate.iam.audit.application.OutboxPublisher.class)
            .length);
    assertEquals(
        0,
        context.getBeanNamesForType(
                com.securegate.iam.identity.application.port.MfaSecretProtector.class)
            .length);
    assertEquals(
        0,
        context.getBeanNamesForType(
                org.springframework.security.oauth2.server.authorization.client
                    .RegisteredClientRepository.class)
            .length);
    assertEquals(
        0,
        context.getBeanNamesForType(org.springframework.security.oauth2.jwt.JwtEncoder.class)
            .length);
    assertEquals(
        0,
        context.getBeanNamesForType(org.springframework.security.oauth2.jwt.JwtDecoder.class)
            .length);
  }

  @Test
  void exposesOnlyMinimalHealthAndProbes() throws Exception {
    for (String path :
        List.of("/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness")) {
      var response = get(path, "probe-123");
      assertEquals(200, response.statusCode());
      JsonNode health = json.readTree(response.body());
      assertEquals("UP", health.path("status").asText());
      assertFalse(health.has("components"));
      assertFalse(health.has("details"));
      if (path.equals("/actuator/health")) {
        assertEquals(json.readTree("[\"liveness\",\"readiness\"]"), health.path("groups"));
      } else {
        assertEquals(1, health.size());
      }
      assertEquals("probe-123", response.headers().firstValue("X-Correlation-ID").orElseThrow());
      assertFalse(response.headers().firstValue("Set-Cookie").isPresent());
    }
  }

  @Test
  void readinessRefusesTrafficWithoutFailingLiveness() throws Exception {
    try {
      AvailabilityChangeEvent.publish(context, ReadinessState.REFUSING_TRAFFIC);
      var readiness = get("/actuator/health/readiness", "not-ready-123");
      assertEquals(503, readiness.statusCode());
      assertEquals("OUT_OF_SERVICE", json.readTree(readiness.body()).path("status").asText());
      assertEquals(200, get("/actuator/health/liveness", "still-live-123").statusCode());
    } finally {
      AvailabilityChangeEvent.publish(context, ReadinessState.ACCEPTING_TRAFFIC);
    }
  }

  @Test
  void deniesBusinessAndUnexposedManagementRoutesWithProblemDetails() throws Exception {
    for (String path :
        List.of(
            "/api/users",
            "/api/admin/users",
            "/api/admin/policies",
            "/oauth2/jwks",
            "/actuator/env",
            "/actuator/metrics",
            "/actuator/metrics/iam.outbox.pending",
            "/actuator",
            "/actuator/health/db")) {
      var response = get(path, "denied-123");
      JsonNode problem = assertProblem(response, 403, "denied-123");
      assertEquals("Forbidden", problem.path("title").asText());
      assertFalse(response.headers().firstValue("Set-Cookie").isPresent());
    }
    var post =
        client.send(
            HttpRequest.newBuilder(uri("/api/users"))
                .header("X-Correlation-ID", "denied-post-123")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertProblem(post, 403, "denied-post-123");
  }

  @Test
  void sanitizesUnexpectedErrorsAndKeepsTheCorrelationId(CapturedOutput output) throws Exception {
    var response = get("/testing/crash?token=secret-query-value", "error-123");
    assertProblem(response, 500, "error-123");
    assertFalse(response.body().contains("never-expose-this"));
    assertFalse(response.body().contains("secret-query-value"));
    assertFalse(response.body().contains("IllegalStateException"));
    assertFalse(response.body().contains("stackTrace"));
    assertFalse(output.getAll().contains("never-expose-this"));
    assertFalse(output.getAll().contains("secret-query-value"));
  }

  @Test
  void sanitizesValidationAndTypeMismatchErrors(CapturedOutput output) throws Exception {
    var validation =
        client.send(
            HttpRequest.newBuilder(uri("/testing/validated"))
                .header("Content-Type", "application/json")
                .header("X-Correlation-ID", "invalid-123")
                .POST(HttpRequest.BodyPublishers.ofString("{\"value\":\"\"}"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertProblem(validation, 400, "invalid-123");
    var mismatch = get("/testing/numeric?value=secret-invalid-number", "type-123");
    assertProblem(mismatch, 400, "type-123");
    assertFalse(mismatch.body().contains("secret-invalid-number"));
    assertFalse(output.getAll().contains("secret-invalid-number"));
  }

  @Test
  void returnsProblemDetailsForMissingResourceAndUnsupportedMethod() throws Exception {
    assertProblem(get("/testing/missing", "missing-123"), 404, "missing-123");
    var method =
        client.send(
            HttpRequest.newBuilder(uri("/testing/crash"))
                .header("X-Correlation-ID", "method-123")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertProblem(method, 405, "method-123");
    assertTrue(method.headers().firstValue("Allow").orElseThrow().contains("GET"));
  }

  @Test
  void replacesInvalidCorrelationIdsAndDoesNotReusePreviousRequestContext() throws Exception {
    var invalid = get("/actuator/health", "invalid value");
    UUID.fromString(invalid.headers().firstValue("X-Correlation-ID").orElseThrow());
    var next =
        client.send(
            HttpRequest.newBuilder(uri("/actuator/health")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    UUID.fromString(next.headers().firstValue("X-Correlation-ID").orElseThrow());
    assertFalse(
        invalid
            .headers()
            .firstValue("X-Correlation-ID")
            .equals(next.headers().firstValue("X-Correlation-ID")));
  }

  private URI uri(String path) {
    return URI.create("http://127.0.0.1:" + port + path);
  }

  private HttpResponse<String> get(String path, String correlationId) throws Exception {
    return client.send(
        HttpRequest.newBuilder(uri(path)).header("X-Correlation-ID", correlationId).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private JsonNode assertProblem(HttpResponse<String> response, int status, String correlationId)
      throws Exception {
    assertEquals(status, response.statusCode(), response.body());
    assertTrue(
        response
            .headers()
            .firstValue("Content-Type")
            .orElseThrow()
            .startsWith("application/problem+json"));
    assertEquals(correlationId, response.headers().firstValue("X-Correlation-ID").orElseThrow());
    JsonNode problem = json.readTree(response.body());
    assertEquals(status, problem.path("status").asInt());
    assertEquals(correlationId, problem.path("correlationId").asText());
    assertEquals("about:blank", problem.path("type").asText());
    return problem;
  }

  // These endpoints and their permissive chain exist only in test sources.
  @RestController
  @RequestMapping("/testing")
  static class ProbeController {
    @GetMapping("/crash")
    void crash() {
      throw new IllegalStateException("password=never-expose-this");
    }

    @PostMapping("/validated")
    void validated(@Valid @RequestBody ProbeRequest request) {}

    @GetMapping("/numeric")
    int numeric(@RequestParam int value) {
      return value;
    }
  }

  record ProbeRequest(@NotBlank String value) {}

  @TestConfiguration(proxyBeanMethods = false)
  static class ProbeSecurity {
    @Bean
    @Order(0)
    SecurityFilterChain testEndpoints(HttpSecurity http) throws Exception {
      return http.securityMatcher("/testing/**")
          .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
          .csrf(AbstractHttpConfigurer::disable)
          .build();
    }
  }
}

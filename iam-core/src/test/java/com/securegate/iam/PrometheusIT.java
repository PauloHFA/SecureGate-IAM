package com.securegate.iam;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "management.endpoint.prometheus.enabled=true",
      "management.metrics.distribution.percentiles-histogram.http.server.requests=true"
    })
class PrometheusIT {
  private static final String METRICS = UUID.randomUUID().toString() + UUID.randomUUID();
  private static final String ADMIN = UUID.randomUUID().toString() + UUID.randomUUID();
  private static final Path TOKEN =
      Path.of("target/metrics-credentials", UUID.randomUUID().toString(), "token");

  @Container
  static final PostgreSQLContainer<?> DATABASE =
      new PostgreSQLContainer<>("postgres:16.15-alpine")
          .withDatabaseName("metrics_test")
          .withUsername("metrics_test")
          .withPassword(UUID.randomUUID().toString());

  @DynamicPropertySource
  static void configuration(DynamicPropertyRegistry registry) throws Exception {
    Files.createDirectories(TOKEN.getParent());
    Files.writeString(TOKEN, METRICS);
    registry.add("IAM_METRICS_TOKEN_FILE", () -> TOKEN.toAbsolutePath().toString());
    registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
    registry.add("spring.datasource.username", DATABASE::getUsername);
    registry.add("spring.datasource.password", DATABASE::getPassword);
    registry.add("iam.bootstrap.enabled", () -> true);
    registry.add("iam.bootstrap.token", () -> ADMIN);
  }

  @AfterAll
  static void removeCredential() throws Exception {
    Files.deleteIfExists(TOKEN);
    Files.deleteIfExists(TOKEN.getParent());
  }

  @org.springframework.beans.factory.annotation.Autowired
  org.springframework.context.ApplicationContext context;

  @Test
  void defaultRuntimeHasNoTracerOrProvider() {
    assertTrue(context.getBeansOfType(io.micrometer.tracing.Tracer.class).isEmpty());
    assertTrue(
        context.getBeansOfType(io.opentelemetry.sdk.trace.SdkTracerProvider.class).isEmpty());
  }

  @LocalServerPort int port;
  private final HttpClient http = HttpClient.newHttpClient();

  @Test
  void exportsHttpHistogramAndJvmWithoutCredentialsOrRawQueryLabels() throws Exception {
    String marker = "query-" + UUID.randomUUID();
    assertEquals(200, get("/actuator/health", null).statusCode());
    assertEquals(
        404, get("/api/admin/users/" + UUID.randomUUID() + "?token=" + marker, ADMIN).statusCode());
    var response = get("/actuator/prometheus", METRICS);
    assertEquals(200, response.statusCode());
    assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("text/plain"));
    assertTrue(response.headers().firstValue("Set-Cookie").isEmpty());
    String body = response.body();
    assertTrue(body.contains("jvm_memory_used_bytes"));
    assertTrue(body.contains("http_server_requests_seconds_bucket"));
    assertTrue(body.contains("application=\"iam-core\""));
    assertTrue(body.contains("uri=\"/api/admin/users/{id}\""));
    for (String value :
        java.util.List.of(
            METRICS, ADMIN, DATABASE.getPassword(), marker, TOKEN.toAbsolutePath().toString())) {
      assertFalse(body.contains(value), "Sensitive value found in Prometheus export");
    }
  }

  @Test
  void requiresDedicatedReadTokenAndRejectsDuplicateHeadersAndWrites() throws Exception {
    assertEquals(401, get("/actuator/prometheus", null).statusCode());
    assertEquals(401, get("/actuator/prometheus", "incorrect-synthetic-token").statusCode());
    assertEquals(401, get("/actuator/prometheus", ADMIN).statusCode());
    assertEquals(
        401,
        http.send(
                request("/actuator/prometheus")
                    .header("Authorization", "Bearer " + METRICS)
                    .header("Authorization", "Bearer " + METRICS)
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.discarding())
            .statusCode());
    assertEquals(
        403,
        http.send(
                request("/actuator/prometheus")
                    .header("Authorization", "Bearer " + METRICS)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build(),
                HttpResponse.BodyHandlers.discarding())
            .statusCode());
    assertEquals(401, get("/api/admin/users", METRICS).statusCode());
    assertEquals(401, get("/actuator/metrics", METRICS).statusCode());
    assertEquals(403, get("/actuator/env", METRICS).statusCode());
  }

  private HttpRequest.Builder request(String path) {
    return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
        .timeout(java.time.Duration.ofSeconds(5));
  }

  private HttpResponse<String> get(String path, String token) throws Exception {
    var request = request(path);
    if (token != null) {
      request.header("Authorization", "Bearer " + token);
    }
    return http.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
  }
}

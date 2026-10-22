package com.securegate.iam;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@AutoConfigureObservability
@ActiveProfiles({"http-tracing", "structured-logs"})
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "management.tracing.sampling.probability=1")
@Import(HttpServerTracingIT.Fixture.class)
class HttpServerTracingIT {
  @Container
  static final PostgreSQLContainer<?> DB =
      new PostgreSQLContainer<>("postgres:16.15-alpine").withPassword(UUID.randomUUID().toString());

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", DB::getJdbcUrl);
    registry.add("spring.datasource.username", DB::getUsername);
    registry.add("spring.datasource.password", DB::getPassword);
  }

  @LocalServerPort int port;
  @Autowired Capture capture;
  @Autowired Tracer tracer;
  final ObjectMapper json = new ObjectMapper();
  final HttpClient http = HttpClient.newHttpClient();

  String id() {
    return UUID.randomUUID().toString().replace("-", "");
  }

  HttpRequest.Builder request(String path) {
    return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
  }

  HttpResponse<String> get(String path, String parent) throws Exception {
    var builder = request(path);
    if (parent != null) builder.header("traceparent", parent);
    return http.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
  }

  SpanData span(String trace) throws Exception {
    long deadline = System.nanoTime() + java.time.Duration.ofSeconds(3).toNanos();
    while (System.nanoTime() < deadline) {
      var result = capture.spans.stream().filter(s -> s.getTraceId().equals(trace)).findFirst();
      if (result.isPresent()) return result.get();
      Thread.sleep(10);
    }
    throw new AssertionError("HTTP span did not finish");
  }

  void bounded(SpanData span) {
    assertThat(span.getName()).isEqualTo("http.server");
    assertThat(span.getAttributes().size()).isEqualTo(3);
    assertThat(span.getAttributes().asMap().keySet())
        .extracting(Object::toString)
        .containsExactlyInAnyOrder("http.method", "http.status_class", "http.failure");
    assertThat(span.getEvents()).isEmpty();
    assertThat(span.getLinks()).isEmpty();
    assertThat(span.getSpanContext().getTraceState().isEmpty()).isTrue();
    assertThat(span.getStatus().getDescription()).isEmpty();
  }

  @Test
  void acceptsRemoteParentCreatesChildAndDropsSensitiveFields() throws Exception {
    String trace = id(), parent = id().substring(0, 16), marker = "synthetic-" + UUID.randomUUID();
    var response =
        http.send(
            request("/trace-test/echo?token=" + marker)
                .header("traceparent", "00-" + trace + "-" + parent + "-01")
                .header("tracestate", "vendor=" + marker)
                .header("baggage", "token=" + marker)
                .header("Authorization", "Bearer " + marker)
                .header("Cookie", "fixture=" + marker)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    var body = json.readTree(response.body());
    assertThat(body.get("traceId").asText()).isEqualTo(trace);
    assertThat(body.get("spanId").asText()).isNotEqualTo(parent);
    assertThat(body.get("mdcTraceId").asText()).isEqualTo(trace);
    assertThat(body.get("mdcSpanId").asText()).isEqualTo(body.get("spanId").asText());
    var data = span(trace);
    assertThat(data.getParentSpanId()).isEqualTo(parent);
    bounded(data);
    assertThat(data.toString()).doesNotContain(marker, "trace-test", "token=", "Bearer");
    assertThat(tracer.currentSpan()).isNull();
  }

  @Test
  void rejectsDuplicateParentAndDoesNotReuseItOnNextRequest() throws Exception {
    String trace = id(), parent = "00-" + trace + "-" + id().substring(0, 16) + "-01";
    var response =
        http.send(
            request("/trace-test/echo")
                .header("traceparent", parent)
                .header("traceparent", parent)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    String first = json.readTree(response.body()).get("traceId").asText();
    assertThat(first).isNotEqualTo(trace);
    String next = json.readTree(get("/trace-test/echo", null).body()).get("traceId").asText();
    assertThat(next).isNotEqualTo(first).isNotEqualTo(trace);
    assertThat(span(next).getParentSpanId()).isEqualTo("0".repeat(16));
    bounded(span(first));
  }

  @Test
  void handlesFailuresDenialsAndRemoteUnsampledBitUsingLocalPolicy() throws Exception {
    String trace = id();
    assertThat(
            get("/trace-test/failure", "00-" + trace + "-" + id().substring(0, 16) + "-00")
                .statusCode())
        .isEqualTo(500);
    var failure = span(trace);
    bounded(failure);
    assertThat(failure.getAttributes().asMap().toString())
        .contains("5xx", "true")
        .doesNotContain("synthetic-exception-secret");
    assertThat(failure.getSpanContext().isSampled()).isTrue();
    trace = id();
    assertThat(
            get("/actuator/metrics", "00-" + trace + "-" + id().substring(0, 16) + "-01")
                .statusCode())
        .isEqualTo(403);
    bounded(span(trace));
  }

  @Autowired VaultFixture vaultFixture;

  @Test
  void propagatesServerContextThroughRealJdkVaultSessionAndRestoresParent() throws Exception {
    String trace = id();
    var response = get("/trace-test/vault", "00-" + trace + "-" + id().substring(0, 16) + "-01");
    assertThat(response.statusCode()).isEqualTo(200);
    var body = json.readTree(response.body());
    String serverSpan = body.get("serverSpan").asText();
    assertThat(body.get("restoredSpan").asText()).isEqualTo(serverSpan);
    var clients =
        capture.spans.stream()
            .filter(
                data ->
                    data.getTraceId().equals(trace)
                        && data.getKind() == io.opentelemetry.api.trace.SpanKind.CLIENT)
            .toList();
    assertThat(clients).hasSize(3);
    for (var data : clients) {
      assertThat(data.getName()).isEqualTo("vault.http");
      assertThat(data.getParentSpanId()).isEqualTo(serverSpan);
      assertThat(data.getAttributes().size()).isEqualTo(4);
      assertThat(data.getEvents()).isEmpty();
      assertThat(data.toString())
          .doesNotContain(
              vaultFixture.token,
              "secret_id",
              "role_id",
              "localhost",
              "127.0.0.1",
              "username",
              "password");
      assertThat(vaultFixture.parents)
          .anyMatch(parent -> parent.contains(data.getSpanId()) && parent.contains(trace));
    }
    assertThat(vaultFixture.contextExtras).isEmpty();
  }

  @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

  @Test
  void persistsActualHttpServerSpanInOutboxOutsideTheEventPayload() throws Exception {
    String trace = id();
    var response = get("/trace-test/outbox", "00-" + trace + "-" + id().substring(0, 16) + "-01");
    assertThat(response.statusCode()).isEqualTo(200);
    var body = json.readTree(response.body());
    String correlation = body.get("correlation").asText();
    var row =
        jdbc.queryForMap(
            "SELECT trace_parent,payload::text AS payload FROM iam_audit.outbox_events WHERE payload->>'correlationId'=?",
            correlation);
    assertThat(row.get("trace_parent"))
        .isEqualTo("00-" + trace + "-" + body.get("serverSpan").asText() + "-01");
    assertThat(row.get("payload").toString())
        .doesNotContain(trace, "traceparent", "tracestate", "baggage");
  }

  static class VaultFixture implements AutoCloseable {
    final com.sun.net.httpserver.HttpServer server;
    final String token = UUID.randomUUID().toString();
    final java.nio.file.Path directory, role, secret;
    final ConcurrentLinkedQueue<String> parents = new ConcurrentLinkedQueue<>();
    final ConcurrentLinkedQueue<String> contextExtras = new ConcurrentLinkedQueue<>();

    VaultFixture() throws Exception {
      directory = java.nio.file.Files.createTempDirectory("securegate-vault-trace-");
      role =
          java.nio.file.Files.writeString(directory.resolve("role"), UUID.randomUUID().toString());
      secret =
          java.nio.file.Files.writeString(
              directory.resolve("secret"), UUID.randomUUID().toString());
      server =
          com.sun.net.httpserver.HttpServer.create(
              new java.net.InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/v1/",
          exchange -> {
            String parent = exchange.getRequestHeaders().getFirst("traceparent");
            if (parent != null) parents.add(parent);
            for (String key : java.util.List.of("baggage", "tracestate", "b3")) {
              if (exchange.getRequestHeaders().containsKey(key)) contextExtras.add(key);
            }
            String path = exchange.getRequestURI().getPath();
            Object result =
                path.endsWith("login")
                    ? Map.of(
                        "auth",
                        Map.of("client_token", token, "lease_duration", 60, "renewable", true))
                    : Map.of(
                        "data",
                        Map.of("username", "synthetic-user", "password", "synthetic-password"));
            byte[] bytes = new ObjectMapper().writeValueAsBytes(result);
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) {
              output.write(bytes);
            }
            exchange.close();
          });
      server.start();
    }

    URI address() {
      return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    public void close() throws Exception {
      server.stop(0);
      java.nio.file.Files.deleteIfExists(role);
      java.nio.file.Files.deleteIfExists(secret);
      java.nio.file.Files.deleteIfExists(directory);
    }
  }

  static class Capture implements SpanProcessor {
    final ConcurrentLinkedQueue<SpanData> spans = new ConcurrentLinkedQueue<>();

    public void onStart(Context parent, ReadWriteSpan span) {}

    public boolean isStartRequired() {
      return false;
    }

    public void onEnd(ReadableSpan span) {
      spans.add(span.toSpanData());
    }

    public boolean isEndRequired() {
      return true;
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class Fixture {
    @Bean
    Capture capture() {
      return new Capture();
    }

    @Bean
    Endpoint endpoint(
        Tracer tracer,
        com.securegate.iam.shared.infrastructure.VaultHttpTransport transport,
        VaultFixture vault,
        com.securegate.iam.audit.application.port.AuditTrail audit) {
      return new Endpoint(tracer, transport, vault, audit);
    }

    @Bean(destroyMethod = "close")
    VaultFixture vaultFixture() throws Exception {
      return new VaultFixture();
    }

    @Bean
    @Order(-1000)
    SecurityFilterChain fixtureRoutes(HttpSecurity http) throws Exception {
      return http.securityMatcher("/trace-test/**")
          .authorizeHttpRequests(a -> a.anyRequest().permitAll())
          .build();
    }
  }

  @RestController
  @TestComponent
  static class Endpoint {
    final Tracer tracer;

    final com.securegate.iam.shared.infrastructure.VaultHttpTransport transport;
    final VaultFixture vault;
    final com.securegate.iam.audit.application.port.AuditTrail audit;

    Endpoint(
        Tracer tracer,
        com.securegate.iam.shared.infrastructure.VaultHttpTransport transport,
        VaultFixture vault,
        com.securegate.iam.audit.application.port.AuditTrail audit) {
      this.tracer = tracer;
      this.transport = transport;
      this.vault = vault;
      this.audit = audit;
    }

    @GetMapping("/trace-test/outbox")
    Map<String, String> outbox() {
      String correlation = UUID.randomUUID().toString();
      audit.record(
          new com.securegate.iam.audit.application.port.AuditRecord(
              new com.securegate.events.SecurityEventType("test.outbox"),
              "synthetic",
              new com.securegate.iam.shared.application.port.OperationContext("test", correlation),
              Map.of("version", "1")));
      return Map.of(
          "correlation", correlation, "serverSpan", tracer.currentSpan().context().spanId());
    }

    @GetMapping("/trace-test/vault")
    Map<String, String> vault() {
      String serverSpan = tracer.currentSpan().context().spanId();
      try (var session =
          new com.securegate.iam.shared.infrastructure.VaultDatabaseSession(
              vault.address(), vault.role, vault.secret, transport)) {
        session.request("database/creds/iam-runtime", null);
      }
      return Map.of(
          "serverSpan", serverSpan, "restoredSpan", tracer.currentSpan().context().spanId());
    }

    @GetMapping("/trace-test/echo")
    Map<String, String> echo() {
      var context = tracer.currentSpan().context();
      org.slf4j.LoggerFactory.getLogger(getClass())
          .info("synthetic-free-message {}", "synthetic-log-secret");
      return Map.of(
          "traceId",
          context.traceId(),
          "spanId",
          context.spanId(),
          "mdcTraceId",
          String.valueOf(MDC.get("traceId")),
          "mdcSpanId",
          String.valueOf(MDC.get("spanId")));
    }

    @GetMapping("/trace-test/failure")
    void failure() {
      throw new IllegalStateException("synthetic-exception-secret");
    }
  }
}

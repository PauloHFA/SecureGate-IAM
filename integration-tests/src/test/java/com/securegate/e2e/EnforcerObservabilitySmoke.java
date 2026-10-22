package com.securegate.e2e;

import static org.junit.jupiter.api.Assertions.*;

import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Opt-in: -Dit.test=EnforcerObservabilitySmoke. Exercises versioned Compose against real IAM JAR.
 */
class EnforcerObservabilitySmoke {
  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

  @Test
  void realEnforcerEventIsScrapedAndWrongReadTokenIsIsolated() throws Exception {
    Path output =
        Path.of("target/enforcer-observability-smoke", UUID.randomUUID().toString())
            .toAbsolutePath();
    Path workspace = output.resolve("workspace");
    Path scripts = workspace.resolve("infra/scripts");
    Files.createDirectories(scripts);
    try (var paths = Files.walk(Path.of("../infra/observability"))) {
      for (Path source : paths.toList()) {
        Path target =
            workspace
                .resolve("infra/observability")
                .resolve(Path.of("../infra/observability").relativize(source));
        if (Files.isDirectory(source)) {
          Files.createDirectories(target);
        } else {
          Files.copy(source, target);
        }
      }
    }
    for (String name : List.of("Initialize-Observability.ps1", "Start-Observability.ps1")) {
      Files.copy(Path.of("../infra/scripts", name), scripts.resolve(name));
    }
    Path privateRoot = workspace.resolve("infra/.local");
    Files.createDirectories(privateRoot);
    Path tokenFile = privateRoot.resolve("iam-metrics-token");
    Path grafanaPassword = privateRoot.resolve("observability/grafana-admin-password");
    String token = UUID.randomUUID().toString() + UUID.randomUUID();
    Files.writeString(tokenFile, token);
    Path enforcerTokenFile = privateRoot.resolve("enforcer-metrics-token");
    String enforcerToken = UUID.randomUUID().toString() + UUID.randomUUID();
    Files.writeString(enforcerTokenFile, enforcerToken);
    String project =
        "securegate-obs-test-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    try (var environment = new PropagationEnvironment()) {
      environment.start();
      environment.stopIam();
      environment.startIam(
          Map.of(
              "IAM_PROMETHEUS_ENABLED",
              "true",
              "IAM_METRICS_TOKEN_FILE",
              tokenFile.toString(),
              "IAM_HTTP_HISTOGRAMS_ENABLED",
              "true"));
      int enforcerPort = port();
      environment.restartEnforcer(
          Map.of(
              "ENFORCER_METRICS_ENABLED",
              "true",
              "ENFORCER_METRICS_PORT",
              String.valueOf(enforcerPort),
              "ENFORCER_METRICS_TOKEN_FILE",
              enforcerTokenFile.toString()));
      URI enforcerMetrics = URI.create("http://127.0.0.1:" + enforcerPort + "/metrics");
      PropagationEnvironment.await(
          "enforcer metrics listener",
          Duration.ofSeconds(30),
          () ->
              HTTP.send(
                          HttpRequest.newBuilder(enforcerMetrics)
                              .timeout(Duration.ofSeconds(3))
                              .header("Authorization", "Bearer " + enforcerToken)
                              .GET()
                              .build(),
                          HttpResponse.BodyHandlers.discarding())
                      .statusCode()
                  == 200);
      assertEquals(
          401,
          HTTP.send(
                  HttpRequest.newBuilder(enforcerMetrics)
                      .header("Authorization", "Bearer " + token)
                      .GET()
                      .build(),
                  HttpResponse.BodyHandlers.discarding())
              .statusCode());
      int prometheusPort = port(), grafanaPort = port();
      while (grafanaPort == prometheusPort || grafanaPort == environment.base.getPort()) {
        grafanaPort = port();
      }
      assertEquals(
          0,
          script(
              output.resolve("initialize.log"),
              scripts.resolve("Initialize-Observability.ps1").toString(),
              "-IamPort",
              String.valueOf(environment.base.getPort()),
              "-PrometheusPort",
              String.valueOf(prometheusPort),
              "-GrafanaPort",
              String.valueOf(grafanaPort),
              "-IncludeEnforcer",
              "-EnforcerPort",
              String.valueOf(enforcerPort)));
      String password = Files.readString(grafanaPassword);
      assertTrue(password.matches("[0-9a-f]{64}"));
      assertNotEquals(
          0,
          script(
              output.resolve("initialize-again.log"),
              scripts.resolve("Initialize-Observability.ps1").toString()));
      assertEquals(password, Files.readString(grafanaPassword));
      assertEquals(
          0,
          script(
              output.resolve("start.log"),
              scripts.resolve("Start-Observability.ps1").toString(),
              "-ProjectName",
              project));
      URI prom = URI.create("http://127.0.0.1:" + prometheusPort);
      URI grafana = URI.create("http://127.0.0.1:" + grafanaPort);
      PropagationEnvironment.await(
          "Prometheus scrapes host loopback IAM",
          Duration.ofSeconds(30),
          () -> "1".equals(value(prom, "up{job=\"iam-core\"}")));
      assertNotEquals("NaN", value(prom, "sum(jvm_memory_used_bytes{application=\"iam-core\"})"));
      assertNotEquals(
          "NaN", value(prom, "sum(http_server_requests_seconds_bucket{application=\"iam-core\"})"));
      PropagationEnvironment.await(
          "Prometheus scrapes enforcer",
          Duration.ofSeconds(30),
          () -> "1".equals(value(prom, "up{job=\"policy-enforcer\"}")));
      assertNotEquals(
          "NaN", value(prom, "sum(jvm_memory_used_bytes{application=\"policy-enforcer\"})"));
      String email = "metrics-" + UUID.randomUUID() + "@example.test";
      String userPassword = UUID.randomUUID().toString() + UUID.randomUUID();
      var created =
          HTTP.send(
              HttpRequest.newBuilder(environment.base.resolve("/api/admin/users"))
                  .header("Authorization", "Bearer " + environment.admin)
                  .header("Content-Type", "application/json")
                  .POST(
                      HttpRequest.BodyPublishers.ofString(
                          PropagationEnvironment.JSON.writeValueAsString(
                              Map.of(
                                  "email",
                                  email,
                                  "displayName",
                                  "Synthetic metrics",
                                  "password",
                                  userPassword))))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(201, created.statusCode());
      String userId = PropagationEnvironment.JSON.readTree(created.body()).path("id").asText();
      var locked =
          HTTP.send(
              HttpRequest.newBuilder(
                      environment.base.resolve("/api/admin/users/" + userId + "/status"))
                  .header("Authorization", "Bearer " + environment.admin)
                  .header("Content-Type", "application/json")
                  .method(
                      "PATCH",
                      HttpRequest.BodyPublishers.ofString("{\"status\":\"LOCKED\",\"version\":0}"))
                  .build(),
              HttpResponse.BodyHandlers.discarding());
      assertEquals(200, locked.statusCode());
      PropagationEnvironment.await(
          "real locked fact projected and exported",
          Duration.ofSeconds(30),
          () -> {
            String projection =
                environment
                    .redis
                    .opsForValue()
                    .get(
                        com.securegate.events.SecurityProjectionKeys.key(
                            "block-policy", "identity", userId));
            String measured =
                value(
                    prom,
                    "sum(iam_enforcer_block_policy_messages_total{application=\"policy-enforcer\",result=\"ACK\"})");
            var facts =
                environment.jdbc.queryForList(
                    "SELECT event_id FROM iam_audit.outbox_events WHERE event_type='user.locked' AND published_at IS NOT NULL",
                    UUID.class);
            boolean canonicalProcessed =
                facts.size() == 1
                    && Boolean.TRUE.equals(
                        environment.redis.hasKey(
                            "securegate:{block-policy}:event:" + facts.getFirst()));
            return canonicalProcessed
                && projection != null
                && projection.endsWith("|LOCKED")
                && !measured.equals("NaN")
                && Double.parseDouble(measured) >= 1;
          });
      String basic =
          Base64.getEncoder()
              .encodeToString(("admin:" + password).getBytes(StandardCharsets.UTF_8));
      var anonymous = get(grafana.resolve("/api/dashboards/uid/securegate-enforcer"), null);
      assertEquals(401, anonymous.statusCode());
      var dashboard = get(grafana.resolve("/api/dashboards/uid/securegate-enforcer"), basic);
      assertEquals(200, dashboard.statusCode());
      var body = PropagationEnvironment.JSON.readTree(dashboard.body());
      assertEquals(6, body.path("dashboard").path("panels").size());
      assertTrue(body.path("meta").path("provisioned").asBoolean());
      for (var panel : body.path("dashboard").path("panels")) {
        for (var target : panel.path("targets")) {
          var expression =
              get(
                  prom.resolve(
                      "/api/v1/query?query="
                          + URLEncoder.encode(
                              target.path("expr").asText(), StandardCharsets.UTF_8)),
                  null);
          assertEquals(
              200, expression.statusCode(), "Dashboard query must parse in real Prometheus");
          assertEquals(
              "success",
              PropagationEnvironment.JSON.readTree(expression.body()).path("status").asText());
        }
      }
      String queryBody =
          PropagationEnvironment.JSON.writeValueAsString(
              Map.of(
                  "from",
                  "now-5m",
                  "to",
                  "now",
                  "queries",
                  List.of(
                      Map.of(
                          "refId",
                          "A",
                          "expr",
                          "sum(iam_enforcer_block_policy_messages_total{application=\"policy-enforcer\",result=\"ACK\"})",
                          "instant",
                          true,
                          "range",
                          false,
                          "datasource",
                          Map.of("uid", "securegate-prometheus", "type", "prometheus")))));
      var proxied =
          HTTP.send(
              HttpRequest.newBuilder(grafana.resolve("/api/ds/query"))
                  .timeout(Duration.ofSeconds(10))
                  .header("Authorization", "Basic " + basic)
                  .header("Content-Type", "application/json")
                  .POST(HttpRequest.BodyPublishers.ofString(queryBody))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, proxied.statusCode());
      assertFalse(
          PropagationEnvironment.JSON
              .readTree(proxied.body())
              .path("results")
              .path("A")
              .path("frames")
              .isEmpty(),
          "Grafana datasource must return a real metric frame");
      var data = get(grafana.resolve("/api/datasources/uid/securegate-prometheus/health"), basic);
      assertEquals(200, data.statusCode());
      assertEquals("OK", PropagationEnvironment.JSON.readTree(data.body()).path("status").asText());
      // Re-reading credentials_file must surface rejection; container readiness alone is
      // insufficient.
      Files.writeString(enforcerTokenFile, UUID.randomUUID().toString() + UUID.randomUUID());
      PropagationEnvironment.await(
          "invalid scrape credential is visible as up=0",
          Duration.ofSeconds(25),
          () -> "0".equals(value(prom, "up{job=\"policy-enforcer\"}")));
      var targets = get(prom.resolve("/api/v1/targets"), null);
      assertTrue(targets.body().contains("401"), "The target must fail at enforcer authentication");
      assertEquals("1", value(prom, "up{job=\"iam-core\"}"));
      Files.writeString(enforcerTokenFile, enforcerToken);
      PropagationEnvironment.await(
          "restored enforcer scrape credential recovers",
          Duration.ofSeconds(25),
          () -> "1".equals(value(prom, "up{job=\"policy-enforcer\"}")));
      // No token/admin credentials are embedded in templates, dashboard or Compose startup logs.
      try (var paths = Files.walk(output)) {
        for (Path path :
            paths.filter(Files::isRegularFile).filter(p -> !p.startsWith(privateRoot)).toList()) {
          String artifact = Files.readString(path);
          for (String secret :
              List.of(
                  token,
                  enforcerToken,
                  password,
                  basic,
                  environment.admin,
                  environment.database.getPassword(),
                  email,
                  userId,
                  userPassword)) {
            assertFalse(
                artifact.contains(secret), "Credential leaked into observability artifacts");
          }
        }
      }
      Files.writeString(
          output.resolve("result.json"),
          "{\"scrape\":true,\"jvm\":true,\"httpBuckets\":true,\"dashboardPanels\":6,\"anonymousDenied\":true,\"wrongTokenDetected\":true,\"recovered\":true}\n");
      System.out.println(
          "Enforcer observability smoke: real user.locked projection and ACK scrape; 6 panels; wrong token isolated from IAM and recovered; artifacts checked.");
    } finally {
      try {
        // This generated project owns only the disposable collector's containers and volumes.
        if (Files.isRegularFile(privateRoot.resolve("observability/compose.env"))) {
          var cleanup =
              new ProcessBuilder(
                      "docker",
                      "compose",
                      "--env-file",
                      privateRoot.resolve("observability/compose.env").toString(),
                      "-f",
                      workspace.resolve("infra/observability/docker-compose.yml").toString(),
                      "-p",
                      project,
                      "down",
                      "--volumes")
                  .redirectErrorStream(true)
                  .redirectOutput(output.resolve("cleanup.log").toFile())
                  .start();
          try {
            assertTrue(cleanup.waitFor(45, TimeUnit.SECONDS));
            assertEquals(0, cleanup.exitValue());
          } finally {
            if (cleanup.isAlive()) {
              cleanup.destroyForcibly();
            }
          }
        }
      } finally {
        Files.deleteIfExists(tokenFile);
        Files.deleteIfExists(enforcerTokenFile);
        Files.deleteIfExists(grafanaPassword);
      }
    }
  }

  private static int port() throws Exception {
    try (var socket = new ServerSocket(0, 0, java.net.InetAddress.getByName("127.0.0.1"))) {
      return socket.getLocalPort();
    }
  }

  private static HttpResponse<String> get(URI uri, String basic) throws Exception {
    var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5));
    if (basic != null) {
      request.header("Authorization", "Basic " + basic);
    }
    return HTTP.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
  }

  private static String value(URI base, String query) throws Exception {
    var response =
        get(
            base.resolve("/api/v1/query?query=" + URLEncoder.encode(query, StandardCharsets.UTF_8)),
            null);
    if (response.statusCode() != 200) {
      return "NaN";
    }
    var values = PropagationEnvironment.JSON.readTree(response.body()).path("data").path("result");
    return values.isEmpty() ? "NaN" : values.get(0).path("value").get(1).asText();
  }

  private int script(Path log, String name, String... arguments) throws Exception {
    boolean windows = System.getProperty("os.name").startsWith("Windows");
    var command =
        new ArrayList<>(
            List.of(
                System.getProperty("securegate.powershell", windows ? "powershell.exe" : "pwsh"),
                "-NoProfile",
                "-NonInteractive",
                "-File",
                name));
    command.addAll(List.of(arguments));
    var process =
        new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
    try {
      assertTrue(
          process.waitFor(Duration.ofMinutes(3).toMillis(), TimeUnit.MILLISECONDS),
          "Observability script timed out; inspect target/enforcer-observability-smoke");
      return process.exitValue();
    } finally {
      if (process.isAlive()) {
        process.destroyForcibly();
        process.waitFor(10, TimeUnit.SECONDS);
      }
    }
  }
}

package com.securegate.e2e;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.dockerjava.api.model.AccessMode;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Volume;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
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
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;

/** Bounded, simultaneous metrics/logs/traces collection for real IAM and enforcer processes. */
class JointSignalsIT {
  @TempDir Path temporary;

  @Test
  void collectsThreeSignalsAcrossBothServicesForOneMinute() throws Exception {
    Path iamToken = temporary.resolve("iam-token");
    Path enforcerToken = temporary.resolve("enforcer-token");
    Path prometheusConfig = temporary.resolve("prometheus.yml");
    String iamCredential = UUID.randomUUID().toString() + UUID.randomUUID();
    String enforcerCredential = UUID.randomUUID().toString() + UUID.randomUUID();
    String grafanaCredential = UUID.randomUUID().toString() + UUID.randomUUID();
    Files.writeString(iamToken, iamCredential);
    Files.writeString(enforcerToken, enforcerCredential);
    int enforcerPort = freePort();
    Path iamLog = Path.of("target/e2e/iam-core.log");
    Path enforcerLog = Path.of("target/e2e/policy-enforcer.log");
    long iamOffset = Files.exists(iamLog) ? Files.size(iamLog) : 0;
    long enforcerOffset = Files.exists(enforcerLog) ? Files.size(enforcerLog) : 0;
    try (var network = Network.newNetwork();
        var tempo =
            new GenericContainer<>(
                    TracingBackendIT.image("docker-compose.tracing.yml", "grafana/tempo:"))
                .withNetwork(network)
                .withNetworkAliases("tempo")
                .withExposedPorts(3200, 4318)
                .withCommand("-target=all", "-config.file=/etc/tempo.yml")
                .withCreateContainerCmdModifier(
                    cmd ->
                        cmd.getHostConfig()
                            .withBinds(
                                new Bind(
                                    TracingBackendIT.ROOT.resolve("tracing/tempo.yml").toString(),
                                    new Volume("/etc/tempo.yml"),
                                    AccessMode.ro)))
                .waitingFor(Wait.forHttp("/ready").forPort(3200))
                .withStartupTimeout(Duration.ofSeconds(120))) {
      tempo.start();
      URI backend = URI.create("http://127.0.0.1:" + tempo.getMappedPort(3200));
      String receiver = "http://127.0.0.1:" + tempo.getMappedPort(4318) + "/v1/traces";
      var tracing =
          Map.of(
              "SECUREGATE_TRACING_EXPORT_ENABLED",
              "true",
              "SECUREGATE_TRACING_EXPORT_ENDPOINT",
              receiver);
      try (var environment = new PropagationEnvironment(tracing, true)) {
        environment.start();
        environment.stopIam();
        environment.startIam(
            Map.of(
                "IAM_PROMETHEUS_ENABLED", "true",
                "IAM_METRICS_TOKEN_FILE", iamToken.toString(),
                "IAM_HTTP_HISTOGRAMS_ENABLED", "true"));
        environment.restartEnforcer(
            Map.of(
                "ENFORCER_METRICS_ENABLED", "true",
                "ENFORCER_METRICS_PORT", Integer.toString(enforcerPort),
                "ENFORCER_METRICS_TOKEN_FILE", enforcerToken.toString()));
        Files.writeString(
            prometheusConfig,
            Files.readString(TracingBackendIT.ROOT.resolve("prometheus.template.yml"))
                    .replace("__IAM_PORT__", Integer.toString(environment.base.getPort()))
                + Files.readString(TracingBackendIT.ROOT.resolve("enforcer-scrape.template.yml"))
                    .replace("__ENFORCER_PORT__", Integer.toString(enforcerPort)));
        try (var prometheus =
                new GenericContainer<>(
                        TracingBackendIT.image("docker-compose.yml", "prom/prometheus:"))
                    .withNetwork(network)
                    .withNetworkAliases("prometheus")
                    .withExposedPorts(9090)
                    .withCommand("--config.file=/etc/prometheus/prometheus.yml")
                    .withCreateContainerCmdModifier(
                        cmd ->
                            cmd.getHostConfig()
                                .withBinds(
                                    new Bind(
                                        prometheusConfig.toString(),
                                        new Volume("/etc/prometheus/prometheus.yml"),
                                        AccessMode.ro),
                                    new Bind(
                                        iamToken.toString(),
                                        new Volume("/run/secrets/iam-metrics-token"),
                                        AccessMode.ro),
                                    new Bind(
                                        enforcerToken.toString(),
                                        new Volume("/run/secrets/enforcer-metrics-token"),
                                        AccessMode.ro)))
                    .waitingFor(Wait.forHttp("/-/ready").forPort(9090))
                    .withStartupTimeout(Duration.ofSeconds(90));
            var grafana =
                new GenericContainer<>(
                        TracingBackendIT.image("docker-compose.yml", "grafana/grafana:"))
                    .withNetwork(network)
                    .withExposedPorts(3000)
                    .withEnv("GF_SECURITY_ADMIN_USER", "admin")
                    .withEnv("GF_SECURITY_ADMIN_PASSWORD", grafanaCredential)
                    .withEnv("GF_AUTH_ANONYMOUS_ENABLED", "false")
                    .withEnv("GF_USERS_ALLOW_SIGN_UP", "false")
                    .withEnv("GF_ANALYTICS_REPORTING_ENABLED", "false")
                    .withEnv("GF_ANALYTICS_CHECK_FOR_UPDATES", "false")
                    .withEnv("GF_PLUGINS_CHECK_FOR_UPDATES", "false")
                    .withEnv("GF_PLUGINS_PREINSTALL_DISABLED", "true")
                    .withCreateContainerCmdModifier(
                        cmd ->
                            cmd.getHostConfig()
                                .withBinds(
                                    new Bind(
                                        TracingBackendIT.ROOT
                                            .resolve("grafana/provisioning")
                                            .toString(),
                                        new Volume("/etc/grafana/provisioning"),
                                        AccessMode.ro),
                                    new Bind(
                                        TracingBackendIT.ROOT
                                            .resolve("tracing/grafana-tempo.yml")
                                            .toString(),
                                        new Volume(
                                            "/etc/grafana/provisioning/datasources/tempo.yml"),
                                        AccessMode.ro)))
                    .waitingFor(Wait.forHttp("/api/health"))
                    .withStartupTimeout(Duration.ofSeconds(90))) {
          prometheus.start();
          grafana.start();
          URI metrics = URI.create("http://127.0.0.1:" + prometheus.getMappedPort(9090));
          URI ui = URI.create("http://127.0.0.1:" + grafana.getMappedPort(3000));
          PropagationEnvironment.await(
              "both service scrapes healthy",
              Duration.ofSeconds(45),
              () ->
                  "1".equals(value(metrics, "up{job=\"iam-core\"}"))
                      && "1".equals(value(metrics, "up{job=\"policy-enforcer\"}")));
          String auth =
              "Basic "
                  + Base64.getEncoder()
                      .encodeToString(
                          ("admin:" + grafanaCredential).getBytes(StandardCharsets.UTF_8));
          assertEquals(
              401,
              TracingBackendIT.get(
                      PropagationEnvironment.HTTP,
                      ui.resolve("/api/datasources/uid/securegate-prometheus"),
                      null)
                  .statusCode());
          assertEquals(
              200,
              TracingBackendIT.get(
                      PropagationEnvironment.HTTP,
                      ui.resolve("/api/datasources/uid/securegate-prometheus"),
                      auth)
                  .statusCode());
          assertEquals(
              200,
              TracingBackendIT.get(
                      PropagationEnvironment.HTTP,
                      ui.resolve("/api/datasources/uid/securegate-tempo"),
                      auth)
                  .statusCode());

          var ids = new ArrayList<String>();
          long started = System.nanoTime();
          for (int i = 0; i < 12; i++) {
            ids.add(TracingJourneyIT.lockUser(environment));
            assertEquals("1", value(metrics, "up{job=\"iam-core\"}"));
            assertEquals("1", value(metrics, "up{job=\"policy-enforcer\"}"));
            Thread.sleep(5000);
          }
          assertTrue(Duration.ofNanos(System.nanoTime() - started).toSeconds() >= 60);
          assertTrue(
              Double.parseDouble(value(metrics, "count_over_time(up{job=\"iam-core\"}[1m])")) >= 8);
          assertTrue(
              Double.parseDouble(value(metrics, "count_over_time(up{job=\"policy-enforcer\"}[1m])"))
                  >= 8);
          assertTrue(
              Double.parseDouble(
                      value(
                          metrics,
                          "sum(iam_enforcer_block_policy_messages_total{application=\"policy-enforcer\",result=\"ACK\"})"))
                  >= 12);
          for (int index : List.of(0, 5, 11)) {
            String traceId = TracingJourneyIT.traceId(environment, ids.get(index));
            JsonNode trace = TracingJourneyIT.awaitChain(backend, traceId);
            assertFalse(trace.toString().contains(iamCredential));
            assertFalse(trace.toString().contains(enforcerCredential));
            URI proxied =
                ui.resolve("/api/datasources/proxy/uid/securegate-tempo/api/traces/" + traceId);
            assertTrue(
                TracingJourneyIT.names(
                        TracingBackendIT.awaitTrace(PropagationEnvironment.HTTP, proxied, auth))
                    .contains("security.consume"));
          }
          assertStructured(iamLog, iamOffset, "iam-core", iamCredential);
          assertStructured(enforcerLog, enforcerOffset, "policy-enforcer", enforcerCredential);
        }
      }
    }
  }

  private static String value(URI prometheus, String expression) throws Exception {
    URI query =
        prometheus.resolve(
            "/api/v1/query?query=" + URLEncoder.encode(expression, StandardCharsets.UTF_8));
    var response =
        PropagationEnvironment.HTTP.send(
            HttpRequest.newBuilder(query).timeout(Duration.ofSeconds(5)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode());
    var results = PropagationEnvironment.JSON.readTree(response.body()).path("data").path("result");
    return results.isEmpty() ? "NaN" : results.get(0).path("value").get(1).asText();
  }

  private static void assertStructured(Path log, long offset, String service, String secret)
      throws Exception {
    byte[] bytes = Files.readAllBytes(log);
    String output =
        new String(
            bytes,
            Math.toIntExact(offset),
            bytes.length - Math.toIntExact(offset),
            StandardCharsets.UTF_8);
    assertFalse(output.contains(secret));
    var lines = output.lines().filter(line -> line.startsWith("{")).limit(20).toList();
    assertFalse(lines.isEmpty(), "Structured operational logs must be emitted");
    for (String line : lines) {
      var record = PropagationEnvironment.JSON.readTree(line);
      var names = new java.util.HashSet<String>();
      record.fieldNames().forEachRemaining(names::add);
      assertEquals(
          Set.of("timestamp", "level", "service", "event", "correlationHash", "traceId", "spanId"),
          names);
      assertEquals(service, record.path("service").asText());
    }
  }

  private static int freePort() throws Exception {
    try (var socket = new ServerSocket(0, 0, java.net.InetAddress.getByName("127.0.0.1"))) {
      return socket.getLocalPort();
    }
  }
}

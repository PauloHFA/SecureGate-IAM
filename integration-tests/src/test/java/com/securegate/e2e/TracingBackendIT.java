package com.securegate.e2e;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.model.AccessMode;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.Volume;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;

/** Backend fixture with synthetic OTLP; TracingJourneyIT covers real application exporters. */
class TracingBackendIT {
  static final ObjectMapper JSON = new ObjectMapper();
  static final Path ROOT = Path.of("../infra/observability").toAbsolutePath().normalize();
  @TempDir Path temporary;

  static String image(String file, String prefix) throws Exception {
    var matcher =
        Pattern.compile("image: (" + Pattern.quote(prefix) + "[^\\s]+)")
            .matcher(Files.readString(ROOT.resolve(file)));
    assertTrue(matcher.find(), "Versioned image required");
    assertTrue(matcher.group(1).contains("@sha256:"));
    return matcher.group(1);
  }

  @Test
  void optionalComposeOverlayPreservesBaseAndRestrictsPorts() throws Exception {
    var base = compose(false);
    assertFalse(base.path("services").has("tempo"));
    var traced = compose(true);
    var tempo = traced.path("services").path("tempo");
    assertEquals("10001:10001", tempo.path("user").asText());
    assertTrue(tempo.path("read_only").asBoolean());
    assertEquals(2, tempo.path("ports").size());
    for (var port : tempo.path("ports")) assertEquals("127.0.0.1", port.path("host_ip").asText());
    assertEquals(
        List.of(3200, 4318),
        JSON.convertValue(tempo.path("ports"), List.class).stream()
            .map(value -> ((Map<?, ?>) value).get("target"))
            .toList());
    assertTrue(
        traced.path("services").path("grafana").path("volumes").toString().contains("tempo.yml"));
    assertTrue(traced.path("volumes").has("tempo-data"));
  }

  JsonNode compose(boolean tracing) throws Exception {
    Path env = temporary.resolve("compose.env");
    Files.writeString(env, "PROMETHEUS_PORT=19090\nGRAFANA_PORT=13000\n");
    var args =
        new ArrayList<>(
            List.of(
                "docker",
                "compose",
                "--env-file",
                env.toString(),
                "-f",
                ROOT.resolve("docker-compose.yml").toString()));
    if (tracing) args.addAll(List.of("-f", ROOT.resolve("docker-compose.tracing.yml").toString()));
    args.addAll(List.of("config", "--format", "json"));
    var builder =
        new ProcessBuilder(args).redirectError(temporary.resolve("compose-error.log").toFile());
    for (String key :
        List.of(
            "PROMETHEUS_PORT",
            "GRAFANA_PORT",
            "TEMPO_HTTP_PORT",
            "TEMPO_OTLP_PORT",
            "COMPOSE_PROFILES")) builder.environment().remove(key);
    var process = builder.start();
    String result = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertTrue(process.waitFor(30, TimeUnit.SECONDS));
    assertEquals(0, process.exitValue(), "Compose must render without starting services");
    return JSON.readTree(result);
  }

  @Test
  void realBackendStoresLinksSurvivesRestartAndGrafanaRequiresAuthentication() throws Exception {
    String password = UUID.randomUUID().toString() + UUID.randomUUID();
    String volume = "securegate-tempo-test-" + UUID.randomUUID().toString().replace("-", "");
    try (var network = Network.newNetwork();
        var tempo =
            new GenericContainer<>(image("docker-compose.tracing.yml", "grafana/tempo:"))
                .withNetwork(network)
                .withNetworkAliases("tempo")
                .withExposedPorts(3200, 4318)
                .withCommand("-target=all", "-config.file=/etc/tempo.yml")
                .withCreateContainerCmdModifier(
                    cmd ->
                        cmd.getHostConfig()
                            .withBinds(
                                new Bind(
                                    ROOT.resolve("tracing/tempo.yml").toString(),
                                    new Volume("/etc/tempo.yml"),
                                    AccessMode.ro),
                                new Bind(volume, new Volume("/var/tempo")))
                            .withReadonlyRootfs(true)
                            .withMemory(1073741824L)
                            .withCapDrop(Capability.ALL)
                            .withSecurityOpts(List.of("no-new-privileges:true"))
                            .withTmpFs(
                                Map.of("/tmp", "rw,noexec,nosuid,size=16m,uid=10001,gid=10001")))
                .waitingFor(Wait.forHttp("/ready").forPort(3200))
                .withStartupTimeout(Duration.ofSeconds(120));
        var grafana =
            new GenericContainer<>(image("docker-compose.yml", "grafana/grafana:"))
                .withNetwork(network)
                .withExposedPorts(3000)
                .withEnv("GF_SECURITY_ADMIN_USER", "admin")
                .withEnv("GF_SECURITY_ADMIN_PASSWORD", password)
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
                                    ROOT.resolve("grafana/provisioning").toString(),
                                    new Volume("/etc/grafana/provisioning"),
                                    AccessMode.ro),
                                new Bind(
                                    ROOT.resolve("tracing/grafana-tempo.yml").toString(),
                                    new Volume("/etc/grafana/provisioning/datasources/tempo.yml"),
                                    AccessMode.ro)))
                .waitingFor(Wait.forHttp("/api/health"))
                .withStartupTimeout(Duration.ofSeconds(90));
        var http =
            HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build()) {
      try {
        tempo.start();
        grafana.start();
        URI backend = URI.create("http://" + tempo.getHost() + ":" + tempo.getMappedPort(3200));
        URI receiver =
            URI.create(
                "http://" + tempo.getHost() + ":" + tempo.getMappedPort(4318) + "/v1/traces");
        URI ui = URI.create("http://" + grafana.getHost() + ":" + grafana.getMappedPort(3000));
        assertEquals(
            401, get(http, ui.resolve("/api/datasources/uid/securegate-tempo"), null).statusCode());
        String auth =
            "Basic "
                + Base64.getEncoder()
                    .encodeToString(("admin:" + password).getBytes(StandardCharsets.UTF_8));
        var datasource = get(http, ui.resolve("/api/datasources/uid/securegate-tempo"), auth);
        assertEquals(200, datasource.statusCode());
        assertEquals("http://tempo:3200", JSON.readTree(datasource.body()).path("url").asText());
        assertEquals("tempo", JSON.readTree(datasource.body()).path("type").asText());
        var defaultSource =
            get(http, ui.resolve("/api/datasources/uid/securegate-prometheus"), auth);
        assertTrue(JSON.readTree(defaultSource.body()).path("isDefault").asBoolean());
        String producer = UUID.randomUUID().toString().replace("-", "");
        String batch = UUID.randomUUID().toString().replace("-", "");
        String body = otlp(producer, batch);
        var sent =
            http.send(
                HttpRequest.newBuilder(receiver)
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, sent.statusCode(), "Synthetic OTLP must be accepted");
        assertEquals(
            0, JSON.readTree(sent.body()).path("partialSuccess").path("rejectedSpans").asInt());
        var stored = awaitTrace(http, backend.resolve("/api/traces/" + batch), null);
        assertTrue(stored.findValuesAsText("name").contains("synthetic.batch"));
        var links = stored.findValues("links");
        assertTrue(
            links.stream().anyMatch(value -> value.isArray() && !value.isEmpty()),
            "Batch-to-producer link must survive storage");
        var linked =
            links.stream()
                .filter(value -> value.isArray() && !value.isEmpty())
                .findFirst()
                .orElseThrow()
                .get(0);
        assertTrue(
            List.of(producer, Base64.getEncoder().encodeToString(HexFormat.of().parseHex(producer)))
                .contains(linked.path("traceId").asText()));
        awaitTrace(http, backend.resolve("/api/traces/" + producer), null);
        var proxied =
            awaitTrace(
                http,
                ui.resolve("/api/datasources/proxy/uid/securegate-tempo/api/traces/" + batch),
                auth);
        assertTrue(proxied.findValuesAsText("name").contains("synthetic.batch"));
        // Restart only this disposable backend; preserve the same Docker volume.
        tempo.getDockerClient().restartContainerCmd(tempo.getContainerId()).withTimeout(30).exec();
        // Docker may allocate another ephemeral host port when restarting this container.
        var inspected = tempo.getDockerClient().inspectContainerCmd(tempo.getContainerId()).exec();
        int restartedPort =
            Integer.parseInt(
                inspected
                    .getNetworkSettings()
                    .getPorts()
                    .getBindings()
                    .get(new com.github.dockerjava.api.model.ExposedPort(3200))[0]
                    .getHostPortSpec());
        backend = URI.create("http://" + tempo.getHost() + ":" + restartedPort);
        awaitReady(http, backend.resolve("/ready"));
        var recovered = awaitTrace(http, backend.resolve("/api/traces/" + batch), null);
        assertTrue(recovered.findValuesAsText("name").contains("synthetic.batch"));
        assertTrue(
            recovered.findValues("links").stream()
                .anyMatch(value -> value.isArray() && !value.isEmpty()));
        awaitTrace(
            http,
            ui.resolve("/api/datasources/proxy/uid/securegate-tempo/api/traces/" + producer),
            auth);
        var config = get(http, backend.resolve("/status/config"), null);
        assertEquals(200, config.statusCode());
        assertTrue(
            config.body().contains("block_retention: 24h"), "Effective retention must be bounded");
      } finally {
        tempo.stop();
        try {
          tempo.getDockerClient().removeVolumeCmd(volume).exec();
        } catch (com.github.dockerjava.api.exception.NotFoundException absent) {
          /* Startup may fail before volume creation. */
        }
      }
    }
  }

  static String otlp(String producer, String batch) throws Exception {
    String span = "1".repeat(16);
    String now = Long.toString(System.currentTimeMillis() * 1000000L);
    var common =
        Map.of(
            "scope",
            Map.of("name", "securegate.backend.test"),
            "spans",
            List.of(
                Map.of(
                    "traceId",
                    producer,
                    "spanId",
                    span,
                    "name",
                    "synthetic.producer",
                    "kind",
                    4,
                    "startTimeUnixNano",
                    now,
                    "endTimeUnixNano",
                    now),
                Map.of(
                    "traceId",
                    batch,
                    "spanId",
                    "2".repeat(16),
                    "name",
                    "synthetic.batch",
                    "kind",
                    5,
                    "startTimeUnixNano",
                    now,
                    "endTimeUnixNano",
                    now,
                    "links",
                    List.of(Map.of("traceId", producer, "spanId", span)))));
    return JSON.writeValueAsString(
        Map.of(
            "resourceSpans",
            List.of(
                Map.of(
                    "resource",
                    Map.of(
                        "attributes",
                        List.of(
                            Map.of(
                                "key",
                                "service.name",
                                "value",
                                Map.of("stringValue", "securegate-synthetic")))),
                    "scopeSpans",
                    List.of(common)))));
  }

  static HttpResponse<String> get(HttpClient http, URI uri, String auth) throws Exception {
    var request =
        HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(5))
            .header("Accept", "application/json");
    if (auth != null) request.header("Authorization", auth);
    return http.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
  }

  static JsonNode awaitTrace(HttpClient http, URI uri, String auth) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
    do {
      try {
        var response = get(http, uri, auth);
        if (response.statusCode() == 200) {
          var value = JSON.readTree(response.body());
          if (!value.findValues("name").isEmpty()) return value;
        }
      } catch (java.io.IOException ignored) {
      }
      Thread.sleep(250);
    } while (System.nanoTime() < deadline);
    throw new AssertionError("Synthetic trace not queryable within bounded wait");
  }

  static void awaitReady(HttpClient http, URI uri) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
    do {
      try {
        if (get(http, uri, null).statusCode() == 200) return;
      } catch (java.io.IOException ignored) {
      }
      Thread.sleep(250);
    } while (System.nanoTime() < deadline);
    throw new AssertionError("Disposable backend not ready after restart");
  }
}

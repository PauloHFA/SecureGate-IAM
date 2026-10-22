package com.securegate.e2e;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.ConnectionFactory;
import java.net.InetAddress;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Opt-in: -Dit.test=RabbitMqObservabilitySmoke. Uses only disposable Compose projects. */
class RabbitMqObservabilitySmoke {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

  @Test
  void aggregatedBrokerMetricsReachPrometheusAndRecoverAfterPause() throws Exception {
    Path output =
        Path.of("target/rabbitmq-observability-smoke", UUID.randomUUID().toString())
            .toAbsolutePath();
    Path workspace = output.resolve("workspace");
    Path scripts = workspace.resolve("infra/scripts");
    Files.createDirectories(scripts);
    for (String dir : List.of("rabbitmq", "observability")) {
      try (var paths = Files.walk(Path.of("../infra", dir))) {
        for (Path source : paths.toList()) {
          Path target =
              workspace
                  .resolve("infra")
                  .resolve(dir)
                  .resolve(Path.of("../infra", dir).relativize(source));
          if (Files.isDirectory(source)) Files.createDirectories(target);
          else Files.copy(source, target);
        }
      }
    }
    for (String name : List.of("docker-compose.yml", "docker-compose.rabbitmq-metrics.yml"))
      Files.copy(Path.of("../infra", name), workspace.resolve("infra").resolve(name));
    Files.copy(Path.of("../.env.example"), workspace.resolve(".env.example"));
    for (String name :
        List.of(
            "Initialize-Local.ps1",
            "LocalEnvironment.ps1",
            "Initialize-MetricsToken.ps1",
            "Initialize-Observability.ps1",
            "Enable-RabbitMqCollection.ps1",
            "Start-Observability.ps1"))
      Files.copy(Path.of("../infra/scripts", name), scripts.resolve(name));
    Path env = workspace.resolve(".env");
    Path privateRoot = workspace.resolve("infra/.local");
    String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    String brokerProject = "securegate-rabbit-test-" + suffix;
    String collectorProject = "securegate-obs-test-" + suffix;
    Path compose = workspace.resolve("infra/docker-compose.yml");
    Path overlay = workspace.resolve("infra/docker-compose.rabbitmq-metrics.yml");
    Path collectorCompose = workspace.resolve("infra/observability/docker-compose.yml");
    Path collectorEnv = privateRoot.resolve("observability/compose.env");
    List<String> broker =
        List.of(
            "docker",
            "compose",
            "--env-file",
            env.toString(),
            "-f",
            compose.toString(),
            "-f",
            overlay.toString(),
            "-p",
            brokerProject);
    List<String> collector =
        List.of(
            "docker",
            "compose",
            "--env-file",
            collectorEnv.toString(),
            "-f",
            collectorCompose.toString(),
            "-p",
            collectorProject);
    try {
      script(output, scripts, "Initialize-Local.ps1", true);
      var settings = new HashMap<String, String>();
      for (String line : Files.readAllLines(env)) {
        if (line.matches("[A-Z_]+=.*")) {
          String[] pair = line.split("=", 2);
          settings.put(pair[0], pair[1]);
        }
      }
      // Reserve distinct loopback ports together, then release them immediately before startup.
      var sockets = new ArrayList<ServerSocket>();
      int amqp, management, metrics, prometheus, grafana, iam;
      try {
        for (int i = 0; i < 6; i++)
          sockets.add(new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1")));
        amqp = sockets.get(0).getLocalPort();
        management = sockets.get(1).getLocalPort();
        metrics = sockets.get(2).getLocalPort();
        prometheus = sockets.get(3).getLocalPort();
        grafana = sockets.get(4).getLocalPort();
        iam = sockets.get(5).getLocalPort();
      } finally {
        for (var socket : sockets) socket.close();
      }
      settings.put("RABBITMQ_PORT", String.valueOf(amqp));
      settings.put("RABBITMQ_MANAGEMENT_PORT", String.valueOf(management));
      settings.put("RABBITMQ_METRICS_PORT", String.valueOf(metrics));
      StringBuilder content = new StringBuilder();
      settings.forEach((key, val) -> content.append(key).append('=').append(val).append('\n'));
      Files.writeString(env, content);
      // The default stack has no metrics publication; every merged publication is loopback-only.
      Path configJson = output.resolve("compose-shape.json");
      run(
          configJson,
          List.of(
              "docker",
              "compose",
              "--env-file",
              env.toString(),
              "-f",
              compose.toString(),
              "config",
              "--format",
              "json",
              "--no-env-resolution"));
      var base = JSON.readTree(Files.readString(configJson));
      assertEquals(2, base.path("services").path("rabbitmq").path("ports").size());
      Files.delete(
          configJson); // Compose output includes generated environment; never keep it as evidence.
      run(configJson, plus(broker, "config", "--format", "json", "--no-env-resolution"));
      var merged = JSON.readTree(Files.readString(configJson));
      var ports = merged.path("services").path("rabbitmq").path("ports");
      assertEquals(3, ports.size());
      for (var publication : ports) assertEquals("127.0.0.1", publication.path("host_ip").asText());
      assertTrue(ports.toString().contains("15692"));
      Files.delete(configJson);
      run(output.resolve("broker-config.log"), plus(broker, "config", "--quiet"));
      for (String line : Files.readAllLines(workspace.resolve("infra/rabbitmq/rabbitmq.conf")))
        if (!line.isBlank())
          assertTrue(
              Files.readString(workspace.resolve("infra/rabbitmq/rabbitmq.metrics.conf"))
                  .contains(line));
      script(output, scripts, "Initialize-MetricsToken.ps1", true);
      script(
          output,
          scripts,
          "Initialize-Observability.ps1",
          true,
          "-IamPort",
          "" + iam,
          "-PrometheusPort",
          "" + prometheus,
          "-GrafanaPort",
          "" + grafana);
      Path scrape = privateRoot.resolve("observability/prometheus.yml");
      String original = Files.readString(scrape);
      String password =
          Files.readString(privateRoot.resolve("observability/grafana-admin-password"));
      String originalEnv = Files.readString(collectorEnv);
      script(
          output,
          scripts,
          "Enable-RabbitMqCollection.ps1",
          false,
          "-RabbitMqMetricsPort",
          "" + prometheus);
      assertEquals(original, Files.readString(scrape));
      script(
          output,
          scripts,
          "Enable-RabbitMqCollection.ps1",
          true,
          "-RabbitMqMetricsPort",
          "" + metrics);
      assertEquals(
          original, Files.readString(scrape.resolveSibling("prometheus.yml.before-rabbitmq")));
      script(
          output,
          scripts,
          "Enable-RabbitMqCollection.ps1",
          false,
          "-RabbitMqMetricsPort",
          "" + metrics);
      assertEquals(originalEnv, Files.readString(collectorEnv));
      assertEquals(
          password, Files.readString(privateRoot.resolve("observability/grafana-admin-password")));
      run(
          output.resolve("broker-start.log"),
          plus(broker, "up", "-d", "--no-deps", "--wait", "--wait-timeout", "120", "rabbitmq"));
      script(output, scripts, "Start-Observability.ps1", true, "-ProjectName", collectorProject);
      URI prom = URI.create("http://127.0.0.1:" + prometheus);
      URI graf = URI.create("http://127.0.0.1:" + grafana);
      URI endpoint = URI.create("http://127.0.0.1:" + metrics + "/metrics");
      PropagationEnvironment.await(
          "RabbitMQ scrape",
          Duration.ofSeconds(45),
          () -> "1".equals(value(prom, "up{job=\"rabbitmq\"}")));
      var factory = new ConnectionFactory();
      factory.setHost("127.0.0.1");
      factory.setPort(amqp);
      factory.setUsername(settings.get("RABBITMQ_USER"));
      factory.setPassword(settings.get("RABBITMQ_PASSWORD"));
      factory.setVirtualHost(settings.get("RABBITMQ_VHOST"));
      String queue = "smoke-" + UUID.randomUUID();
      try (var connection = factory.newConnection();
          var channel = connection.createChannel()) {
        channel.queueDeclare(queue, false, true, true, null);
        channel.confirmSelect();
        channel.basicPublish("", queue, null, new byte[] {1});
        channel.basicPublish("", queue, null, new byte[] {2});
        channel.waitForConfirmsOrDie(5000);
        var delivery = channel.basicGet(queue, false);
        assertNotNull(delivery);
        PropagationEnvironment.await(
            "ready and unacked from actual AMQP delivery",
            Duration.ofSeconds(45),
            () ->
                Double.parseDouble(
                            value(prom, "sum(rabbitmq_queue_messages_ready{job=\"rabbitmq\"})"))
                        >= 1
                    && Double.parseDouble(
                            value(prom, "sum(rabbitmq_queue_messages_unacked{job=\"rabbitmq\"})"))
                        >= 1);
        String raw = get(endpoint, null).body();
        assertFalse(raw.contains(queue), "The aggregated scrape must not label individual queues");
        assertFalse(raw.contains(settings.get("RABBITMQ_PASSWORD")));
        String basic =
            Base64.getEncoder()
                .encodeToString(("admin:" + password).getBytes(StandardCharsets.UTF_8));
        assertEquals(
            401, get(graf.resolve("/api/dashboards/uid/securegate-rabbitmq"), null).statusCode());
        var dashboard = get(graf.resolve("/api/dashboards/uid/securegate-rabbitmq"), basic);
        assertEquals(200, dashboard.statusCode());
        JsonNode panels = JSON.readTree(dashboard.body()).path("dashboard").path("panels");
        assertEquals(6, panels.size());
        for (var panel : panels)
          for (var target : panel.path("targets")) {
            String expression = target.path("expr").asText();
            assertNotEquals(
                "NaN", value(prom, expression), "Dashboard expression must return broker data");
          }
        String body =
            JSON.writeValueAsString(
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
                            "rabbitmq_queue_messages_unacked{job=\"rabbitmq\"}",
                            "instant",
                            true,
                            "datasource",
                            Map.of("uid", "securegate-prometheus", "type", "prometheus")))));
        var proxied =
            HTTP.send(
                HttpRequest.newBuilder(graf.resolve("/api/ds/query"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Basic " + basic)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, proxied.statusCode());
        assertFalse(
            JSON.readTree(proxied.body()).path("results").path("A").path("frames").isEmpty());
        channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
        PropagationEnvironment.await(
            "ACK removes unacked",
            Duration.ofSeconds(45),
            () ->
                "0".equals(value(prom, "sum(rabbitmq_queue_messages_unacked{job=\"rabbitmq\"})")));
      }
      run(output.resolve("pause.log"), plus(broker, "pause", "rabbitmq"));
      try {
        PropagationEnvironment.await(
            "paused broker scrape fails",
            Duration.ofSeconds(45),
            () -> "0".equals(value(prom, "up{job=\"rabbitmq\"}")));
      } finally {
        run(output.resolve("unpause.log"), plus(broker, "unpause", "rabbitmq"));
      }
      PropagationEnvironment.await(
          "broker scrape recovers",
          Duration.ofSeconds(45),
          () -> "1".equals(value(prom, "up{job=\"rabbitmq\"}")));
      // No production IAM is used: this test covers the dependency collector alone.
      assertEquals("0", value(prom, "up{job=\"iam-core\"}"));
      var secrets = new ArrayList<String>();
      for (var entry : settings.entrySet())
        if (entry.getKey().contains("PASSWORD") || entry.getKey().contains("TOKEN"))
          secrets.add(entry.getValue());
      secrets.add(password);
      secrets.add(
          Base64.getEncoder()
              .encodeToString(("admin:" + password).getBytes(StandardCharsets.UTF_8)));
      secrets.add(Files.readString(privateRoot.resolve("iam-metrics-token")));
      try (var paths = Files.walk(output)) {
        for (Path path :
            paths
                .filter(Files::isRegularFile)
                .filter(p -> !p.startsWith(privateRoot) && !p.equals(env))
                .toList()) {
          String artifact = Files.readString(path);
          for (String secret : secrets)
            assertFalse(artifact.contains(secret), "Secret in non-private artifact");
        }
      }
      Files.writeString(
          output.resolve("result.json"),
          "{\"aggregated\":true,\"readyAndUnacked\":true,\"ack\":true,\"panels\":6,\"grafanaFrame\":true,\"failureAndRecovery\":true,\"secretScan\":true}\n");
      System.out.println(
          "RabbitMQ observability smoke: real AMQP ready/unacked/ACK, six panels, Grafana frame and scrape failure/recovery passed.");
    } finally {
      try {
        if (Files.isRegularFile(collectorEnv))
          run(output.resolve("collector-cleanup.log"), plus(collector, "down", "--volumes"));
      } finally {
        try {
          if (Files.isRegularFile(env))
            run(output.resolve("broker-cleanup.log"), plus(broker, "down", "--volumes"));
        } finally {
          Files.deleteIfExists(env);
          for (String name :
              List.of(
                  "rabbitmq-definitions.json",
                  "iam-metrics-token",
                  "observability/grafana-admin-password"))
            Files.deleteIfExists(privateRoot.resolve(name));
          Files.deleteIfExists(output.resolve("compose-shape.json"));
        }
      }
    }
  }

  private static List<String> plus(List<String> prefix, String... args) {
    var result = new ArrayList<>(prefix);
    result.addAll(List.of(args));
    return result;
  }

  private static HttpResponse<String> get(URI uri, String basic) throws Exception {
    var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5));
    if (basic != null) request.header("Authorization", "Basic " + basic);
    return HTTP.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
  }

  private static String value(URI prom, String expression) throws Exception {
    var response =
        get(
            prom.resolve(
                "/api/v1/query?query=" + URLEncoder.encode(expression, StandardCharsets.UTF_8)),
            null);
    assertEquals(200, response.statusCode());
    var result = JSON.readTree(response.body()).path("data").path("result");
    return result.isEmpty() ? "NaN" : result.get(0).path("value").get(1).asText();
  }

  private static void script(
      Path output, Path scripts, String name, boolean success, String... args) throws Exception {
    String shell =
        System.getProperty(
            "securegate.powershell",
            System.getProperty("os.name").startsWith("Windows") ? "powershell.exe" : "pwsh");
    int code =
        process(
            output.resolve(name + (success ? ".log" : ".rejected.log")),
            plus(
                List.of(
                    shell,
                    "-NoProfile",
                    "-NonInteractive",
                    "-File",
                    scripts.resolve(name).toString()),
                args));
    if (success) assertEquals(0, code, name);
    else assertNotEquals(0, code, name);
  }

  private static void run(Path log, List<String> command) throws Exception {
    assertEquals(0, process(log, command), "Process failed; inspect disposable smoke logs");
  }

  private static int process(Path log, List<String> command) throws Exception {
    var process =
        new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
    try {
      assertTrue(process.waitFor(3, TimeUnit.MINUTES), "Smoke process timeout");
      return process.exitValue();
    } finally {
      if (process.isAlive()) {
        process.destroyForcibly();
        process.waitFor(10, TimeUnit.SECONDS);
      }
    }
  }
}

package com.securegate.e2e;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.AclSetuserArgs;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.protocol.CommandType;
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
import org.junit.jupiter.api.function.Executable;

/**
 * Opt-in: -Dit.test=RedisObservabilitySmoke. ACL and real collectors in disposable Compose
 * projects.
 */
class RedisObservabilitySmoke {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

  @Test
  void statisticsAclSurvivesRestartAndCannotReadBusinessKeys() throws Exception {
    Path output =
        Path.of("target/redis-observability-smoke", UUID.randomUUID().toString()).toAbsolutePath();
    Path workspace = output.resolve("workspace"),
        scripts = workspace.resolve("infra/scripts"),
        privateRoot = workspace.resolve("infra/.local");
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
    for (String file : List.of("docker-compose.yml", "docker-compose.redis-metrics.yml"))
      Files.copy(Path.of("../infra", file), workspace.resolve("infra").resolve(file));
    Files.copy(Path.of("../.env.example"), workspace.resolve(".env.example"));
    for (String name :
        List.of(
            "Initialize-Local.ps1",
            "LocalEnvironment.ps1",
            "Initialize-MetricsToken.ps1",
            "Initialize-Observability.ps1",
            "Enable-RedisCollection.ps1",
            "Initialize-RedisMetrics.ps1",
            "Start-Observability.ps1"))
      Files.copy(Path.of("../infra/scripts", name), scripts.resolve(name));
    String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    String redisProject = "securegate-redis-test-" + suffix,
        collectorProject = "securegate-obs-test-" + suffix;
    Path env = workspace.resolve(".env"),
        passwordFile = privateRoot.resolve("redis-metrics-password.json"),
        aclFile = privateRoot.resolve("redis-metrics-users.acl");
    Path compose = workspace.resolve("infra/docker-compose.yml"),
        overlay = workspace.resolve("infra/docker-compose.redis-metrics.yml"),
        collectorEnv = privateRoot.resolve("observability/compose.env");
    List<String> redis =
        List.of(
            "docker",
            "compose",
            "--env-file",
            env.toString(),
            "-f",
            compose.toString(),
            "-p",
            redisProject);
    List<String> exporter =
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
            redisProject);
    List<String> collector =
        List.of(
            "docker",
            "compose",
            "--env-file",
            collectorEnv.toString(),
            "-f",
            workspace.resolve("infra/observability/docker-compose.yml").toString(),
            "-p",
            collectorProject);
    try {
      script(output, scripts, "Initialize-Local.ps1", true);
      var settings = new HashMap<String, String>();
      for (String line : Files.readAllLines(env))
        if (line.matches("[A-Z_]+=.*")) {
          String[] pair = line.split("=", 2);
          settings.put(pair[0], pair[1]);
        }
      var sockets = new ArrayList<ServerSocket>();
      int redisPort, metricsPort, promPort, grafanaPort, iamPort;
      try {
        for (int i = 0; i < 5; i++)
          sockets.add(new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1")));
        redisPort = sockets.get(0).getLocalPort();
        metricsPort = sockets.get(1).getLocalPort();
        promPort = sockets.get(2).getLocalPort();
        grafanaPort = sockets.get(3).getLocalPort();
        iamPort = sockets.get(4).getLocalPort();
      } finally {
        for (var socket : sockets) socket.close();
      }
      settings.put("REDIS_PORT", "" + redisPort);
      settings.put("REDIS_METRICS_PORT", "" + metricsPort);
      var content = new StringBuilder();
      settings.forEach((key, val) -> content.append(key).append('=').append(val).append('\n'));
      Files.writeString(env, content);
      Path shapeFile = output.resolve("compose-shape.json");
      run(shapeFile, plus(redis, "config", "--format", "json"));
      assertFalse(
          JSON.readTree(Files.readString(shapeFile)).path("services").has("redis-exporter"));
      Files.delete(shapeFile);
      run(shapeFile, plus(exporter, "config", "--format", "json"));
      var shape =
          JSON.readTree(Files.readString(shapeFile)).path("services").path("redis-exporter");
      assertEquals("127.0.0.1", shape.path("ports").get(0).path("host_ip").asText());
      assertTrue(shape.path("environment").has("REDIS_PASSWORD_FILE"));
      assertFalse(shape.path("environment").has("REDIS_PASSWORD"));
      Files.delete(shapeFile);
      run(
          output.resolve("redis-start.log"),
          plus(redis, "up", "-d", "--no-deps", "--wait", "--wait-timeout", "90", "redis"));
      String key = "private-key-" + UUID.randomUUID(),
          canary = "private-value-" + UUID.randomUUID();
      var adminClient = client(redisPort, "default", settings.get("REDIS_PASSWORD"));
      try (var admin = adminClient.connect()) {
        var cmd = admin.sync();
        cmd.set(key, canary);
        for (String customUser : List.of("custom_observer", "DEFAULT")) {
          cmd.aclSetuser(
              customUser,
              new AclSetuserArgs().on().nopass().noCommands().addCommand(CommandType.PING));
          try {
            script(
                output,
                scripts,
                "Initialize-RedisMetrics.ps1",
                false,
                "-ProjectName",
                redisProject);
            assertFalse(Files.exists(passwordFile));
          } finally {
            cmd.aclDeluser(customUser);
          }
        }
        script(output, scripts, "Initialize-RedisMetrics.ps1", true, "-ProjectName", redisProject);
        String originalJson = Files.readString(passwordFile),
            originalAcl = Files.readString(aclFile);
        script(output, scripts, "Initialize-RedisMetrics.ps1", true, "-ProjectName", redisProject);
        assertEquals(originalJson, Files.readString(passwordFile));
        assertEquals(originalAcl, Files.readString(aclFile));
        Files.writeString(passwordFile, "{}");
        try {
          script(
              output, scripts, "Initialize-RedisMetrics.ps1", false, "-ProjectName", redisProject);
          assertEquals("{}", Files.readString(passwordFile));
        } finally {
          Files.writeString(passwordFile, originalJson);
        }

        String wrongCaseMap =
            JSON.writeValueAsString(
                Map.of(
                    "redis://SECUREGATE_METRICS@redis:6379",
                    JSON.readTree(originalJson)
                        .path("redis://securegate_metrics@redis:6379")
                        .asText()));
        Files.writeString(passwordFile, wrongCaseMap);
        try {
          script(
              output, scripts, "Initialize-RedisMetrics.ps1", false, "-ProjectName", redisProject);
          assertEquals(wrongCaseMap, Files.readString(passwordFile));
        } finally {
          Files.writeString(passwordFile, originalJson);
        }
        Files.writeString(aclFile, originalAcl + "\n");
        try {
          script(
              output, scripts, "Initialize-RedisMetrics.ps1", false, "-ProjectName", redisProject);
          assertEquals(originalAcl + "\n", Files.readString(aclFile));
        } finally {
          Files.writeString(aclFile, originalAcl);
        }
      } finally {
        adminClient.shutdown();
      }
      String password =
          JSON.readTree(Files.readString(passwordFile))
              .path("redis://securegate_metrics@redis:6379")
              .asText();
      assertTrue(password.matches("[0-9a-f]{64}"));
      assertNotEquals(settings.get("REDIS_PASSWORD"), password);
      run(
          output.resolve("redis-overlay.log"),
          plus(exporter, "up", "-d", "--no-deps", "--wait", "--wait-timeout", "90", "redis"));
      adminClient = client(redisPort, "default", settings.get("REDIS_PASSWORD"));
      var metricsClient = client(redisPort, "securegate_metrics", password);
      try (var admin = adminClient.connect();
          var metrics = metricsClient.connect()) {
        var cmd = admin.sync();
        var limited = metrics.sync();
        assertEquals(canary, cmd.get(key));
        assertTrue(limited.info().contains("redis_version"));
        for (Executable denied :
            List.<Executable>of(
                () -> limited.get(key),
                () -> limited.set(key, "forbidden"),
                () -> limited.scan(),
                () -> limited.configGet("*"),
                () -> limited.aclList(),
                () -> limited.eval("return 1", ScriptOutputType.INTEGER),
                () -> limited.flushdb(),
                () -> limited.slowlogGet(1))) {
          var error = assertThrows(RedisCommandExecutionException.class, denied);
          assertTrue(error.getMessage().startsWith("NOPERM"));
        }
        cmd.aclSetuser(
            "securegate_metrics", new AclSetuserArgs().addCommand(CommandType.GET).allKeys());
        try {
          script(
              output, scripts, "Initialize-RedisMetrics.ps1", false, "-ProjectName", redisProject);
        } finally {
          // Reload this disposable fixture's exact ACL; -GET would leave a redundant rule.
          cmd.aclLoad();
        }
        script(output, scripts, "Initialize-RedisMetrics.ps1", true, "-ProjectName", redisProject);
        script(output, scripts, "Initialize-MetricsToken.ps1", true);
        script(
            output,
            scripts,
            "Initialize-Observability.ps1",
            true,
            "-IamPort",
            "" + iamPort,
            "-PrometheusPort",
            "" + promPort,
            "-GrafanaPort",
            "" + grafanaPort);
        Path scrape = privateRoot.resolve("observability/prometheus.yml");
        String original = Files.readString(scrape),
            originalEnv = Files.readString(collectorEnv),
            grafanaPassword =
                Files.readString(privateRoot.resolve("observability/grafana-admin-password"));
        script(
            output,
            scripts,
            "Enable-RedisCollection.ps1",
            false,
            "-RedisMetricsPort",
            "" + promPort);
        assertEquals(original, Files.readString(scrape));
        script(
            output,
            scripts,
            "Enable-RedisCollection.ps1",
            true,
            "-RedisMetricsPort",
            "" + metricsPort);
        assertEquals(
            original, Files.readString(scrape.resolveSibling("prometheus.yml.before-redis")));
        script(
            output,
            scripts,
            "Enable-RedisCollection.ps1",
            false,
            "-RedisMetricsPort",
            "" + metricsPort);
        assertEquals(originalEnv, Files.readString(collectorEnv));
        assertEquals(
            grafanaPassword,
            Files.readString(privateRoot.resolve("observability/grafana-admin-password")));
        run(
            output.resolve("exporter-start.log"),
            plus(exporter, "up", "-d", "--no-deps", "redis-exporter"));
        script(output, scripts, "Start-Observability.ps1", true, "-ProjectName", collectorProject);
        URI prom = URI.create("http://127.0.0.1:" + promPort),
            graf = URI.create("http://127.0.0.1:" + grafanaPort),
            endpoint = URI.create("http://127.0.0.1:" + metricsPort + "/metrics");
        PropagationEnvironment.await(
            "Redis scrape and INFO",
            Duration.ofSeconds(45),
            () ->
                "1".equals(value(prom, "up{job=\"redis\"}"))
                    && "1".equals(value(prom, "redis_up{job=\"redis\"}")));
        assertEquals("0", value(prom, "redis_exporter_last_scrape_error{job=\"redis\"}"));
        assertTrue(Double.parseDouble(value(prom, "sum(redis_db_keys{job=\"redis\"})")) >= 1);
        String raw = get(endpoint, null).body();
        for (String sensitive : List.of(key, canary, password, settings.get("REDIS_PASSWORD")))
          assertFalse(raw.contains(sensitive));
        assertEquals(
            404,
            get(endpoint.resolve("/scrape?target=redis://securegate_metrics@redis:6379"), null)
                .statusCode());
        String basic =
            Base64.getEncoder()
                .encodeToString(("admin:" + grafanaPassword).getBytes(StandardCharsets.UTF_8));
        assertEquals(
            401, get(graf.resolve("/api/dashboards/uid/securegate-redis"), null).statusCode());
        var dashboard = get(graf.resolve("/api/dashboards/uid/securegate-redis"), basic);
        assertEquals(200, dashboard.statusCode());
        var panels = JSON.readTree(dashboard.body()).path("dashboard").path("panels");
        assertEquals(6, panels.size());
        for (var panel : panels)
          for (var target : panel.path("targets"))
            assertNotEquals("NaN", value(prom, target.path("expr").asText()));
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
                            "redis_up{job=\"redis\"}",
                            "instant",
                            true,
                            "datasource",
                            Map.of("uid", "securegate-prometheus", "type", "prometheus")))));
        var frame =
            HTTP.send(
                HttpRequest.newBuilder(graf.resolve("/api/ds/query"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Basic " + basic)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, frame.statusCode());
        assertFalse(JSON.readTree(frame.body()).path("results").path("A").path("frames").isEmpty());
        String invalid = UUID.randomUUID().toString();
        cmd.aclSetuser("securegate_metrics", new AclSetuserArgs().resetpass().addPassword(invalid));
        PropagationEnvironment.await(
            "wrong Redis credential visible separately from HTTP",
            Duration.ofSeconds(45),
            () ->
                "0".equals(value(prom, "redis_up{job=\"redis\"}"))
                    && "1".equals(value(prom, "up{job=\"redis\"}")));
        cmd.aclSetuser(
            "securegate_metrics", new AclSetuserArgs().resetpass().addPassword(password));
        PropagationEnvironment.await(
            "Redis credential restored",
            Duration.ofSeconds(45),
            () -> "1".equals(value(prom, "redis_up{job=\"redis\"}")));
        run(output.resolve("redis-restart.log"), plus(exporter, "restart", "redis"));
        PropagationEnvironment.await(
            "ACL file survives Redis restart",
            Duration.ofSeconds(45),
            () ->
                "1".equals(value(prom, "redis_up{job=\"redis\"}"))
                    && "PONG".equals(limited.ping()));
        assertEquals(canary, cmd.get(key));
        run(output.resolve("exporter.log"), plus(exporter, "logs", "--no-color", "redis-exporter"));
        var secrets =
            new ArrayList<String>(
                List.of(
                    password,
                    grafanaPassword,
                    basic,
                    invalid,
                    key,
                    canary,
                    Files.readString(privateRoot.resolve("iam-metrics-token"))));
        settings.forEach(
            (k, v) -> {
              if (k.contains("PASSWORD") || k.contains("TOKEN")) secrets.add(v);
            });
        try (var paths = Files.walk(output)) {
          for (Path path :
              paths
                  .filter(Files::isRegularFile)
                  .filter(p -> !p.startsWith(privateRoot) && !p.equals(env))
                  .toList()) {
            String artifact = Files.readString(path);
            for (String secret : secrets)
              assertFalse(
                  artifact.contains(secret), "Sensitive data in non-private smoke artifact");
          }
        }
        Files.writeString(
            output.resolve("result.json"),
            "{\"aclDeniedKeyAccess\":true,\"customAclRejected\":true,\"restartAndDataPreserved\":true,\"panels\":6,\"authFailureAndRecovery\":true,\"secretScan\":true}\n");
        System.out.println(
            "Redis observability smoke: INFO-only ACL, key/command denials, six panels, credential failure/recovery, persistent ACL and AOF data passed.");
      } finally {
        adminClient.shutdown();
        metricsClient.shutdown();
      }
    } finally {
      try {
        if (Files.isRegularFile(collectorEnv))
          run(output.resolve("collector-cleanup.log"), plus(collector, "down", "--volumes"));
      } finally {
        try {
          if (Files.isRegularFile(env))
            run(output.resolve("redis-cleanup.log"), plus(exporter, "down", "--volumes"));
        } finally {
          Files.deleteIfExists(env);
          Files.deleteIfExists(output.resolve("compose-shape.json"));
          for (String name :
              List.of(
                  "redis-metrics-password.json",
                  "redis-metrics-users.acl",
                  "rabbitmq-definitions.json",
                  "iam-metrics-token",
                  "observability/grafana-admin-password"))
            Files.deleteIfExists(privateRoot.resolve(name));
        }
      }
    }
  }

  private static RedisClient client(int port, String username, String password) {
    return RedisClient.create(
        RedisURI.Builder.redis("127.0.0.1", port)
            .withAuthentication(username, password.toCharArray())
            .withTimeout(Duration.ofSeconds(3))
            .build());
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

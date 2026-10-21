package com.securegate.e2e;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Opt-in: -Dit.test=PostgresObservabilitySmoke. Versioned provisioning/Compose in disposable
 * projects.
 */
class PostgresObservabilitySmoke {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

  @Test
  void limitedLoginExportsStatisticsAndDatabaseAuthenticationFailureIsVisible() throws Exception {
    Path output =
        Path.of("target/postgres-observability-smoke", UUID.randomUUID().toString())
            .toAbsolutePath();
    Path workspace = output.resolve("workspace"),
        scripts = workspace.resolve("infra/scripts"),
        privateRoot = workspace.resolve("infra/.local");
    Files.createDirectories(scripts);
    for (String dir : List.of("postgres", "rabbitmq", "observability")) {
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
    for (String file : List.of("docker-compose.yml", "docker-compose.postgres-metrics.yml"))
      Files.copy(Path.of("../infra", file), workspace.resolve("infra").resolve(file));
    Files.copy(Path.of("../.env.example"), workspace.resolve(".env.example"));
    for (String name :
        List.of(
            "Initialize-Local.ps1",
            "LocalEnvironment.ps1",
            "Initialize-MetricsToken.ps1",
            "Initialize-Observability.ps1",
            "Enable-PostgresCollection.ps1",
            "Provision-PostgresMetrics.ps1",
            "Start-Observability.ps1"))
      Files.copy(Path.of("../infra/scripts", name), scripts.resolve(name));
    String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    String databaseProject = "securegate-pg-test-" + suffix,
        collectorProject = "securegate-obs-test-" + suffix;
    Path env = workspace.resolve(".env"),
        passwordFile = privateRoot.resolve("postgres-metrics-password");
    Path compose = workspace.resolve("infra/docker-compose.yml"),
        overlay = workspace.resolve("infra/docker-compose.postgres-metrics.yml");
    Path collectorEnv = privateRoot.resolve("observability/compose.env");
    List<String> database =
        List.of(
            "docker",
            "compose",
            "--env-file",
            env.toString(),
            "-f",
            compose.toString(),
            "-p",
            databaseProject);
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
            databaseProject);
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
      int dbPort, metricsPort, promPort, grafanaPort, iamPort;
      try {
        for (int i = 0; i < 5; i++)
          sockets.add(new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1")));
        dbPort = sockets.get(0).getLocalPort();
        metricsPort = sockets.get(1).getLocalPort();
        promPort = sockets.get(2).getLocalPort();
        grafanaPort = sockets.get(3).getLocalPort();
        iamPort = sockets.get(4).getLocalPort();
      } finally {
        for (var socket : sockets) socket.close();
      }
      settings.put("POSTGRES_PORT", "" + dbPort);
      settings.put("POSTGRES_METRICS_PORT", "" + metricsPort);
      var content = new StringBuilder();
      settings.forEach((key, val) -> content.append(key).append('=').append(val).append('\n'));
      Files.writeString(env, content);
      Path config = output.resolve("compose-shape.json");
      run(config, plus(database, "config", "--format", "json"));
      assertFalse(
          JSON.readTree(Files.readString(config)).path("services").has("postgres-exporter"));
      Files.delete(config);
      run(config, plus(exporter, "config", "--format", "json"));
      var shape =
          JSON.readTree(Files.readString(config)).path("services").path("postgres-exporter");
      assertEquals("127.0.0.1", shape.path("ports").get(0).path("host_ip").asText());
      assertEquals(
          "/run/secrets/postgres-metrics-password",
          shape.path("environment").path("DATA_SOURCE_PASS_FILE").asText());
      assertFalse(shape.path("environment").has("DATA_SOURCE_PASS"));
      Files.delete(config);
      run(
          output.resolve("database-start.log"),
          plus(database, "up", "-d", "--no-deps", "--wait", "--wait-timeout", "90", "postgres"));
      script(
          output, scripts, "Provision-PostgresMetrics.ps1", true, "-ProjectName", databaseProject);
      String password = Files.readString(passwordFile);
      assertTrue(password.matches("[0-9a-f]{64}"));
      assertNotEquals(settings.get("POSTGRES_PASSWORD"), password);
      Files.writeString(passwordFile, password + "\n");
      try {
        script(
            output,
            scripts,
            "Provision-PostgresMetrics.ps1",
            false,
            "-ProjectName",
            databaseProject);
        assertEquals(password + "\n", Files.readString(passwordFile));
      } finally {
        Files.writeString(passwordFile, password);
      }

      String jdbc = "jdbc:postgresql://127.0.0.1:" + dbPort + "/" + settings.get("POSTGRES_DB");
      String canary = "private-" + UUID.randomUUID();
      try (var admin =
              DriverManager.getConnection(
                  jdbc, settings.get("POSTGRES_USER"), settings.get("POSTGRES_PASSWORD"));
          var sql = admin.createStatement()) {
        sql.execute("CREATE SCHEMA iam_metrics_smoke");
        sql.execute("CREATE TABLE iam_metrics_smoke.private_data (value text)");
        sql.execute("INSERT INTO iam_metrics_smoke.private_data VALUES ('" + canary + "')");
        try (var limited = DriverManager.getConnection(jdbc, "securegate_metrics", password);
            var read = limited.createStatement()) {
          try (var rs = read.executeQuery("SHOW default_transaction_read_only")) {
            assertTrue(rs.next());
            assertEquals("on", rs.getString(1));
          }
          read.execute("SET default_transaction_read_only=off");
          for (String denied :
              List.of(
                  "SELECT * FROM iam_metrics_smoke.private_data",
                  "INSERT INTO iam_metrics_smoke.private_data VALUES ('denied')",
                  "CREATE TABLE public.metrics_forbidden (id int)",
                  "SET ROLE " + settings.get("POSTGRES_USER"))) {
            var error = assertThrows(SQLException.class, () -> read.execute(denied));
            assertEquals("42501", error.getSQLState());
          }
          try (var rs =
              read.executeQuery(
                  "SELECT pg_has_role(current_user,'pg_monitor','MEMBER'),rolsuper,rolcreatedb,rolcreaterole,rolreplication,rolbypassrls,rolconnlimit FROM pg_roles WHERE rolname=current_user")) {
            assertTrue(rs.next());
            assertTrue(rs.getBoolean(1));
            for (int i = 2; i <= 6; i++) assertFalse(rs.getBoolean(i));
            assertEquals(3, rs.getInt(7));
          }
        }
        // Refuse a pre-existing login with unexpected application access; preserve the credential
        // file.
        sql.execute("GRANT SELECT ON iam_metrics_smoke.private_data TO securegate_metrics");
        script(
            output,
            scripts,
            "Provision-PostgresMetrics.ps1",
            false,
            "-ProjectName",
            databaseProject);
        assertEquals(password, Files.readString(passwordFile));
        sql.execute("REVOKE SELECT ON iam_metrics_smoke.private_data FROM securegate_metrics");
        script(
            output,
            scripts,
            "Provision-PostgresMetrics.ps1",
            true,
            "-ProjectName",
            databaseProject);
        assertEquals(password, Files.readString(passwordFile));
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
        String original = Files.readString(scrape);
        String originalEnv = Files.readString(collectorEnv),
            grafanaPassword =
                Files.readString(privateRoot.resolve("observability/grafana-admin-password"));
        script(
            output,
            scripts,
            "Enable-PostgresCollection.ps1",
            false,
            "-PostgresMetricsPort",
            "" + promPort);
        assertEquals(original, Files.readString(scrape));
        script(
            output,
            scripts,
            "Enable-PostgresCollection.ps1",
            true,
            "-PostgresMetricsPort",
            "" + metricsPort);
        assertEquals(
            original, Files.readString(scrape.resolveSibling("prometheus.yml.before-postgres")));
        script(
            output,
            scripts,
            "Enable-PostgresCollection.ps1",
            false,
            "-PostgresMetricsPort",
            "" + metricsPort);
        assertEquals(originalEnv, Files.readString(collectorEnv));
        assertEquals(
            grafanaPassword,
            Files.readString(privateRoot.resolve("observability/grafana-admin-password")));
        run(
            output.resolve("exporter-start.log"),
            plus(exporter, "up", "-d", "--no-deps", "postgres-exporter"));
        script(output, scripts, "Start-Observability.ps1", true, "-ProjectName", collectorProject);
        URI prom = URI.create("http://127.0.0.1:" + promPort),
            graf = URI.create("http://127.0.0.1:" + grafanaPort),
            endpoint = URI.create("http://127.0.0.1:" + metricsPort + "/metrics");
        PropagationEnvironment.await(
            "PostgreSQL scrape and SQL connection",
            Duration.ofSeconds(45),
            () ->
                "1".equals(value(prom, "up{job=\"postgres\"}"))
                    && "1".equals(value(prom, "pg_up{job=\"postgres\"}")));
        String commits = "sum(pg_stat_database_xact_commit{job=\"postgres\"})";
        double before = Double.parseDouble(value(prom, commits));
        for (int i = 0; i < 5; i++)
          sql.execute("INSERT INTO iam_metrics_smoke.private_data VALUES ('" + canary + "')");
        PropagationEnvironment.await(
            "real commits reflected",
            Duration.ofSeconds(45),
            () -> Double.parseDouble(value(prom, commits)) > before);
        String raw = get(endpoint, null).body();
        assertTrue(
            raw.contains("pg_stat_bgwriter_"), "Background writer collector must return real data");
        assertFalse(raw.contains(canary));
        assertFalse(raw.contains("private_data"));
        assertFalse(raw.contains(password));
        assertFalse(raw.contains("query="));
        String basic =
            Base64.getEncoder()
                .encodeToString(("admin:" + grafanaPassword).getBytes(StandardCharsets.UTF_8));
        assertEquals(
            401, get(graf.resolve("/api/dashboards/uid/securegate-postgres"), null).statusCode());
        var dashboard = get(graf.resolve("/api/dashboards/uid/securegate-postgres"), basic);
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
                            "pg_up{job=\"postgres\"}",
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
        // Existing SQL sessions would hide a changed credential; terminate only this fixture
        // login's sessions.
        String invalid = UUID.randomUUID().toString();
        sql.execute("ALTER ROLE securegate_metrics PASSWORD '" + invalid + "'");
        sql.execute(
            "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE usename='securegate_metrics'");
        PropagationEnvironment.await(
            "SQL auth failure visible independently of HTTP scrape",
            Duration.ofSeconds(45),
            () ->
                "0".equals(value(prom, "pg_up{job=\"postgres\"}"))
                    && "1".equals(value(prom, "up{job=\"postgres\"}")));
        script(
            output,
            scripts,
            "Provision-PostgresMetrics.ps1",
            true,
            "-ProjectName",
            databaseProject);
        PropagationEnvironment.await(
            "restored SQL credential recovers",
            Duration.ofSeconds(45),
            () -> "1".equals(value(prom, "pg_up{job=\"postgres\"}")));
        var secrets =
            new ArrayList<String>(
                List.of(
                    password,
                    grafanaPassword,
                    basic,
                    invalid,
                    canary,
                    Files.readString(privateRoot.resolve("iam-metrics-token"))));
        settings.forEach(
            (key, val) -> {
              if (key.contains("PASSWORD") || key.contains("TOKEN")) secrets.add(val);
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
            "{\"limitedRole\":true,\"unexpectedAclRejected\":true,\"commits\":true,\"panels\":6,\"authFailureAndRecovery\":true,\"secretScan\":true}\n");
        System.out.println(
            "PostgreSQL observability smoke: limited login, real commits, six panels, SQL authentication failure with HTTP up and recovery passed.");
      }
    } finally {
      try {
        if (Files.isRegularFile(collectorEnv))
          run(output.resolve("collector-cleanup.log"), plus(collector, "down", "--volumes"));
      } finally {
        try {
          if (Files.isRegularFile(env))
            run(output.resolve("database-cleanup.log"), plus(exporter, "down", "--volumes"));
        } finally {
          Files.deleteIfExists(env);
          Files.deleteIfExists(output.resolve("compose-shape.json"));
          for (String name :
              List.of(
                  "postgres-metrics-password",
                  "rabbitmq-definitions.json",
                  "iam-metrics-token",
                  "observability/grafana-admin-password"))
            Files.deleteIfExists(privateRoot.resolve(name));
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

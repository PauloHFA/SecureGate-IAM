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
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Opt-in: -Dit.test=VaultObservabilitySmoke. Authenticated listener in disposable dev Vault. */
class VaultObservabilitySmoke {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

  @Test
  void nativeMetricsUseReadOnlyFiniteTokenAndRotationPreservesTransit() throws Exception {
    Path output =
        Path.of("target/vault-observability-smoke", UUID.randomUUID().toString()).toAbsolutePath();
    Path workspace = output.resolve("workspace"),
        scripts = workspace.resolve("infra/scripts"),
        privateRoot = workspace.resolve("infra/.local");
    Files.createDirectories(scripts);
    for (String dir : List.of("rabbitmq", "vault", "observability")) {
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
    Files.copy(
        Path.of("../infra/docker-compose.yml"), workspace.resolve("infra/docker-compose.yml"));
    Files.copy(Path.of("../.env.example"), workspace.resolve(".env.example"));
    Files.copy(
        Path.of("../infra/docker-compose.vault-metrics.yml"),
        workspace.resolve("infra/docker-compose.vault-metrics.yml"));
    for (String name :
        List.of(
            "Initialize-Local.ps1",
            "LocalEnvironment.ps1",
            "Initialize-MetricsToken.ps1",
            "Initialize-Observability.ps1",
            "Enable-VaultCollection.ps1",
            "Provision-VaultMetrics.ps1",
            "Start-Observability.ps1"))
      Files.copy(Path.of("../infra/scripts", name), scripts.resolve(name));
    String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12),
        vaultProject = "securegate-vault-test-" + suffix,
        collectorProject = "securegate-obs-test-" + suffix;
    Path env = workspace.resolve(".env"),
        tokenFile = privateRoot.resolve("vault-metrics-token"),
        collectorEnv = privateRoot.resolve("observability/compose.env");
    List<String> vaultCompose =
        List.of(
            "docker",
            "compose",
            "--env-file",
            env.toString(),
            "-f",
            workspace.resolve("infra/docker-compose.yml").toString(),
            "-f",
            workspace.resolve("infra/docker-compose.vault-metrics.yml").toString(),
            "-p",
            vaultProject);
    List<String> collector =
        List.of(
            "docker",
            "compose",
            "--env-file",
            collectorEnv.toString(),
            "-f",
            workspace.resolve("infra/observability/docker-compose.yml").toString(),
            "-f",
            workspace.resolve("infra/observability/docker-compose.vault.yml").toString(),
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
      int vaultPort, promPort, grafanaPort, iamPort;
      try {
        for (int i = 0; i < 4; i++)
          sockets.add(new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1")));
        vaultPort = sockets.get(0).getLocalPort();
        promPort = sockets.get(1).getLocalPort();
        grafanaPort = sockets.get(2).getLocalPort();
        iamPort = sockets.get(3).getLocalPort();
      } finally {
        for (var socket : sockets) socket.close();
      }
      settings.put("VAULT_PORT", "" + vaultPort);
      var content = new StringBuilder();
      settings.forEach((k, v) -> content.append(k).append('=').append(v).append('\n'));
      Files.writeString(env, content);
      var plainVault =
          List.of(
              "docker",
              "compose",
              "--env-file",
              env.toString(),
              "-f",
              workspace.resolve("infra/docker-compose.yml").toString(),
              "-p",
              vaultProject);
      run(
          output.resolve("plain-vault-start.log"),
          plus(plainVault, "up", "-d", "--no-deps", "--wait", "--wait-timeout", "90", "vault"));
      assertEquals(
          200,
          get(
                  URI.create("http://127.0.0.1:" + vaultPort + "/v1/sys/metrics?format=prometheus"),
                  null)
              .statusCode());
      script(output, scripts, "Provision-VaultMetrics.ps1", false);
      assertFalse(
          Files.exists(tokenFile),
          "Plain dev metrics must be refused before issuing any credential");
      run(
          output.resolve("vault-start.log"),
          plus(vaultCompose, "up", "-d", "--no-deps", "--wait", "--wait-timeout", "90", "vault"));
      run(
          output.resolve("bootstrap.log"),
          plus(vaultCompose, "run", "--rm", "--no-deps", "vault-init"));
      URI address = URI.create("http://127.0.0.1:" + vaultPort),
          endpoint = address.resolve("/v1/sys/metrics?format=prometheus");
      String root = settings.get("VAULT_DEV_ROOT_TOKEN_ID");
      assertEquals(
          200, vault(address, root, "GET", "/v1/sys/metrics?format=prometheus", null).statusCode());
      assertEquals(403, get(endpoint, null).statusCode());
      String keyBefore =
          JSON.readTree(vault(address, root, "GET", "/v1/transit/keys/securegate-jwt", null).body())
              .path("data")
              .path("keys")
              .toString();
      String appPolicyBefore =
          JSON.readTree(
                  vault(address, root, "GET", "/v1/sys/policies/acl/securegate-iam-core", null)
                      .body())
              .path("data")
              .path("policy")
              .asText();
      String canary = "private-" + UUID.randomUUID();
      assertEquals(
          200,
          vault(
                  address,
                  root,
                  "POST",
                  "/v1/secret/data/metrics-smoke",
                  Map.of("data", Map.of("value", canary)))
              .statusCode());
      script(output, scripts, "Provision-VaultMetrics.ps1", true);
      String token = Files.readString(tokenFile);
      assertFalse(token.equals(root));
      assertTrue(token.length() >= 32);
      assertFalse(token.matches(".*\\s.*"));
      var tokenMetadata =
          JSON.readTree(
                  vault(address, root, "POST", "/v1/auth/token/lookup", Map.of("token", token))
                      .body())
              .path("data");
      assertEquals(1, tokenMetadata.path("policies").size());
      assertEquals("securegate-metrics", tokenMetadata.path("policies").get(0).asText());
      assertFalse(tokenMetadata.path("renewable").asBoolean());
      assertEquals(3600, tokenMetadata.path("explicit_max_ttl").asInt());
      assertTrue(tokenMetadata.path("ttl").asInt() > 0);
      assertTrue(tokenMetadata.path("orphan").asBoolean());
      script(output, scripts, "Provision-VaultMetrics.ps1", true);
      assertTrue(token.equals(Files.readString(tokenFile)), "Valid token should be reused");
      assertEquals(
          200,
          vault(address, token, "GET", "/v1/sys/metrics?format=prometheus", null).statusCode());
      for (String path :
          List.of(
              "/v1/secret/data/securegate/iam-core",
              "/v1/auth/token/lookup-self",
              "/v1/sys/policies/acl/securegate-iam-core"))
        assertEquals(403, vault(address, token, "GET", path, null).statusCode());
      assertEquals(
          403,
          vault(address, token, "POST", "/v1/transit/sign/securegate-jwt", Map.of("input", "AA=="))
              .statusCode());
      assertEquals(
          403, vault(address, token, "POST", "/v1/auth/token/create", Map.of()).statusCode());
      assertEquals(403, vault(address, token, "LIST", "/v1/sys/metrics", null).statusCode());
      String originalPolicy = Files.readString(workspace.resolve("infra/vault/metrics.hcl")).trim();
      assertEquals(
          204,
          vault(
                  address,
                  root,
                  "PUT",
                  "/v1/sys/policies/acl/securegate-metrics",
                  Map.of("policy", "path \"*\" { capabilities = [\"read\"] }"))
              .statusCode());
      try {
        script(output, scripts, "Provision-VaultMetrics.ps1", false);
        assertTrue(token.equals(Files.readString(tokenFile)));
      } finally {
        assertEquals(
            204,
            vault(
                    address,
                    root,
                    "PUT",
                    "/v1/sys/policies/acl/securegate-metrics",
                    Map.of("policy", originalPolicy))
                .statusCode());
      }
      Files.writeString(tokenFile, root);
      try {
        script(output, scripts, "Provision-VaultMetrics.ps1", false, "-Rotate");
        assertTrue(root.equals(Files.readString(tokenFile)));
      } finally {
        Files.writeString(tokenFile, token);
      }
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
          password = Files.readString(privateRoot.resolve("observability/grafana-admin-password"));
      script(output, scripts, "Enable-VaultCollection.ps1", false, "-VaultPort", "" + promPort);
      assertEquals(original, Files.readString(scrape));
      assertEquals(originalEnv, Files.readString(collectorEnv));
      script(output, scripts, "Enable-VaultCollection.ps1", true, "-VaultPort", "" + vaultPort);
      assertEquals(
          original, Files.readString(scrape.resolveSibling("prometheus.yml.before-vault")));
      assertEquals(
          originalEnv, Files.readString(collectorEnv.resolveSibling("compose.env.before-vault")));
      assertTrue(Files.readString(collectorEnv).contains("OBS_VAULT=true"));
      script(output, scripts, "Enable-VaultCollection.ps1", false, "-VaultPort", "" + vaultPort);
      assertTrue(
          password.equals(
              Files.readString(privateRoot.resolve("observability/grafana-admin-password"))));
      script(output, scripts, "Start-Observability.ps1", true, "-ProjectName", collectorProject);
      URI prom = URI.create("http://127.0.0.1:" + promPort),
          graf = URI.create("http://127.0.0.1:" + grafanaPort);
      PropagationEnvironment.await(
          "Native Vault scrape with dedicated token",
          Duration.ofSeconds(45),
          () -> "1".equals(value(prom, "up{job=\"vault\"}")));
      String raw = vault(address, token, "GET", "/v1/sys/metrics?format=prometheus", null).body();
      for (String sensitive : List.of(root, token, canary)) assertFalse(raw.contains(sensitive));
      String basic =
          Base64.getEncoder()
              .encodeToString(("admin:" + password).getBytes(StandardCharsets.UTF_8));
      assertEquals(
          401, get(graf.resolve("/api/dashboards/uid/securegate-vault"), null).statusCode());
      var dashboard = get(graf.resolve("/api/dashboards/uid/securegate-vault"), basic);
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
                          "vault_core_unsealed{job=\"vault\"}",
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
      String invalid = UUID.randomUUID().toString() + UUID.randomUUID();
      Files.writeString(tokenFile, invalid);
      PropagationEnvironment.await(
          "Vault rejects invalid scrape token",
          Duration.ofSeconds(45),
          () -> "0".equals(value(prom, "up{job=\"vault\"}")));
      assertTrue(get(prom.resolve("/api/v1/targets"), null).body().contains("403"));
      Files.writeString(tokenFile, token);
      PropagationEnvironment.await(
          "Vault scrape recovers original token",
          Duration.ofSeconds(45),
          () -> "1".equals(value(prom, "up{job=\"vault\"}")));
      run(output.resolve("stop.log"), plus(collector, "stop"));
      assertEquals(
          204,
          vault(address, root, "POST", "/v1/auth/token/revoke", Map.of("token", token))
              .statusCode());
      script(output, scripts, "Provision-VaultMetrics.ps1", false);
      assertTrue(
          token.equals(Files.readString(tokenFile)),
          "Revoked token is not silently replaced without Rotate");
      script(output, scripts, "Provision-VaultMetrics.ps1", true, "-Rotate");
      String recovered = Files.readString(tokenFile);
      assertFalse(recovered.equals(token));
      assertEquals(
          200,
          vault(address, recovered, "GET", "/v1/sys/metrics?format=prometheus", null).statusCode());
      script(output, scripts, "Provision-VaultMetrics.ps1", true, "-Rotate");
      String rotated = Files.readString(tokenFile);
      assertEquals(
          403,
          vault(address, recovered, "GET", "/v1/sys/metrics?format=prometheus", null).statusCode());
      assertFalse(rotated.equals(token));
      assertEquals(
          403,
          vault(address, token, "GET", "/v1/sys/metrics?format=prometheus", null).statusCode());
      assertEquals(
          200,
          vault(address, rotated, "GET", "/v1/sys/metrics?format=prometheus", null).statusCode());
      run(
          output.resolve("recreate-prometheus.log"),
          plus(
              collector,
              "up",
              "-d",
              "--no-deps",
              "--force-recreate",
              "--wait",
              "--wait-timeout",
              "90",
              "prometheus"));
      PropagationEnvironment.await(
          "New collector binds rotated token and scrapes Vault",
          Duration.ofSeconds(45),
          () -> targetIsUp(prom));
      script(output, scripts, "Start-Observability.ps1", true, "-ProjectName", collectorProject);
      assertEquals(
          keyBefore,
          JSON.readTree(vault(address, root, "GET", "/v1/transit/keys/securegate-jwt", null).body())
              .path("data")
              .path("keys")
              .toString());
      assertEquals(
          appPolicyBefore,
          JSON.readTree(
                  vault(address, root, "GET", "/v1/sys/policies/acl/securegate-iam-core", null)
                      .body())
              .path("data")
              .path("policy")
              .asText());
      assertEquals(
          canary,
          JSON.readTree(vault(address, root, "GET", "/v1/secret/data/metrics-smoke", null).body())
              .path("data")
              .path("data")
              .path("value")
              .asText());
      var secrets =
          new ArrayList<String>(
              List.of(
                  token,
                  recovered,
                  rotated,
                  invalid,
                  password,
                  basic,
                  canary,
                  Files.readString(privateRoot.resolve("iam-metrics-token"))));
      settings.forEach(
          (k, v) -> {
            if (k.contains("PASSWORD") || k.contains("TOKEN")) secrets.add(v);
          });
      for (String role : List.of("iam-core", "policy-enforcer", "iam-runtime", "iam-pam"))
        secrets.add(Files.readString(privateRoot.resolve(role + "-secret-id")));
      try (var paths = Files.walk(output)) {
        for (Path path :
            paths
                .filter(Files::isRegularFile)
                .filter(p -> !p.startsWith(privateRoot) && !p.equals(env))
                .toList()) {
          String artifact = Files.readString(path);
          for (String secret : secrets)
            assertFalse(artifact.contains(secret), "Credential or payload in non-private artifact");
        }
      }
      Files.writeString(
          output.resolve("result.json"),
          "{\"nativeMetrics\":true,\"readOnlyToken\":true,\"ttlSeconds\":3600,\"panels\":6,\"wrongTokenRecovered\":true,\"rotationRevokesOld\":true,\"transitPreserved\":true,\"secretScan\":true}\n");
      System.out.println(
          "Vault observability smoke: native scrape, read-only finite token, six panels, 403/recovery and real rotation; Transit/KV/app policy preserved during token rotation after protected dev startup.");
    } finally {
      try {
        if (Files.isRegularFile(collectorEnv))
          run(output.resolve("collector-cleanup.log"), plus(collector, "down", "--volumes"));
      } finally {
        try {
          if (Files.isRegularFile(env))
            run(output.resolve("vault-cleanup.log"), plus(vaultCompose, "down", "--volumes"));
        } finally {
          Files.deleteIfExists(env);
          for (String name :
              List.of(
                  "vault-metrics-token",
                  "rabbitmq-definitions.json",
                  "iam-metrics-token",
                  "observability/grafana-admin-password"))
            Files.deleteIfExists(privateRoot.resolve(name));
          for (String role : List.of("iam-core", "policy-enforcer", "iam-runtime", "iam-pam"))
            for (String ending : List.of("-secret-id", "-role-id"))
              Files.deleteIfExists(privateRoot.resolve(role + ending));
        }
      }
    }
  }

  private static HttpResponse<String> vault(
      URI base, String token, String method, String path, Object body) throws Exception {
    var request =
        HttpRequest.newBuilder(base.resolve(path))
            .timeout(Duration.ofSeconds(10))
            .header("Authorization", "Bearer " + token);
    if (body == null) request.method(method, HttpRequest.BodyPublishers.noBody());
    else
      request
          .header("Content-Type", "application/json")
          .method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
    return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static boolean targetIsUp(URI prom) throws Exception {
    var response = get(prom.resolve("/api/v1/targets"), null);
    if (response.statusCode() != 200) return false;
    for (var target : JSON.readTree(response.body()).path("data").path("activeTargets"))
      if ("vault".equals(target.path("labels").path("job").asText()))
        return "up".equals(target.path("health").asText());
    return false;
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

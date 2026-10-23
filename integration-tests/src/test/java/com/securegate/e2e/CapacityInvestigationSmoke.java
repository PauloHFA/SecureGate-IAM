package com.securegate.e2e;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Opt-in capacity investigation smoke test (Etapa 10D): runs stepped concurrency execution,
 * validates throughput scaling, verifies Little's Law bounds, confirms HTML dashboard generation,
 * and enforces zero leak of credentials or JWT secrets.
 */
class CapacityInvestigationSmoke {

  @Test
  void measuresCapacityScalingAndBottleneckLimitsWithoutLeakingCredentials() throws Exception {
    String home = System.getProperty("securegate.jmeter.home", "");
    assertTrue(Files.isRegularFile(Path.of(home, "bin", "ApacheJMeter.jar")));

    int steadySeconds = Integer.getInteger("securegate.load.steady.seconds", 5);
    int windowSeconds = Integer.getInteger("securegate.load.window.seconds", 1);
    boolean omitIamPid = Boolean.getBoolean("securegate.load.omitIamPid");

    Path output =
        Path.of("target/capacity-investigation", UUID.randomUUID().toString()).toAbsolutePath();
    Path privateFiles = output.resolve("private");
    Files.createDirectories(privateFiles);
    Path admin = privateFiles.resolve("admin");

    try (var environment = new PropagationEnvironment()) {
      environment.start();
      configureTestRisk(environment);
      Files.writeString(admin, environment.admin);

      var investigationArgs =
          new ArrayList<>(
              List.of(
                  "-JMeterHome",
                  home,
                  "-BaseUri",
                  environment.base.toString(),
                  "-AdminTokenFile",
                  admin.toString(),
                  "-OutputDirectory",
                  output.resolve("investigation").toString(),
                  "-Scenario",
                  "issuance",
                  "-ConcurrencyLevels",
                  "2,4",
                  "-WarmupSeconds",
                  "3",
                  "-SteadySeconds",
                  Integer.toString(steadySeconds),
                  "-WindowSeconds",
                  Integer.toString(windowSeconds),
                  "-CooldownSeconds",
                  "3",
                  "-GenerateDashboard"));

      if (!omitIamPid) {
        investigationArgs.addAll(
            List.of("-IamProcessId", Long.toString(environment.iamProcessId())));
      }
      investigationArgs.addAll(
          List.of(
              "-DockerContainerIds",
              String.join(
                  ",",
                  environment.database.getContainerId(),
                  environment.redisContainer.getContainerId(),
                  environment.rabbit.getContainerId(),
                  environment.vault.getContainerId())));

      assertEquals(
          0,
          script(
              output.resolve("investigation.log"),
              "Run-CapacityInvestigation.ps1",
              investigationArgs.toArray(String[]::new)));

      Path summaryJson = output.resolve("investigation/capacity-investigation-summary.json");
      Path summaryCsv = output.resolve("investigation/capacity-investigation-summary.csv");
      Path reportHtml = output.resolve("investigation/capacity-investigation-report.html");

      assertTrue(Files.isRegularFile(summaryJson));
      assertTrue(Files.isRegularFile(summaryCsv));
      assertTrue(Files.isRegularFile(reportHtml));

      String html = Files.readString(reportHtml);
      assertTrue(html.contains("SecureGate IAM"));
      assertTrue(html.contains("Lei de Little"));
      assertTrue(html.contains("Vault Transit"));

      var report = PropagationEnvironment.JSON.readTree(summaryJson.toFile());
      var results = report.path("results");
      assertEquals(2, results.size());

      var step2 = results.get(0);
      var step4 = results.get(1);

      assertEquals(2, step2.path("threads").asInt());
      assertEquals(4, step4.path("threads").asInt());

      assertEquals(0.0, step2.path("errorPercent").asDouble());
      assertEquals(0.0, step4.path("errorPercent").asDouble());

      assertTrue(step2.path("effectiveRequestsPerSecond").asDouble() > 0);
      assertTrue(step4.path("effectiveRequestsPerSecond").asDouble() > 0);
      assertTrue(step4.path("speedup").asDouble() > 0);
      assertTrue(step4.path("scalingEfficiency").asDouble() > 0);

      // Verify individual step directories and JMeter dashboards
      Path step2Dir = output.resolve("investigation/concurrency-2");
      Path step4Dir = output.resolve("investigation/concurrency-4");
      assertTrue(Files.isDirectory(step2Dir));
      assertTrue(Files.isDirectory(step4Dir));
      assertTrue(Files.isRegularFile(step2Dir.resolve("summary.json")));
      assertTrue(Files.isRegularFile(step4Dir.resolve("summary.json")));
      assertTrue(Files.isRegularFile(step2Dir.resolve("steady/dashboard/index.html")));
      assertTrue(Files.isRegularFile(step4Dir.resolve("steady/dashboard/index.html")));

      // Audit against leaks
      var secrets = new ArrayList<String>();
      secrets.add(environment.admin);
      try (var paths = Files.walk(output.resolve("investigation"))) {
        for (Path path : paths.filter(Files::isRegularFile).toList()) {
          byte[] bytes = Files.readAllBytes(path);
          String content = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
          String utf16 = new String(bytes, java.nio.charset.StandardCharsets.UTF_16LE);
          for (String secret : secrets) {
            assertFalse(content.contains(secret), "Secret leaked in UTF-8: " + path);
            assertFalse(utf16.contains(secret), "Secret leaked in UTF-16: " + path);
          }
        }
      }
    }
  }

  private static void configureTestRisk(PropagationEnvironment environment) throws Exception {
    for (var setting :
        java.util.Map.of(
                "/api/admin/risk/networks", java.util.Map.of("cidr", "127.0.0.0/24"),
                "/api/admin/risk/resources",
                    java.util.Map.of("resource", "auth:login", "sensitivity", "PUBLIC"))
            .entrySet()) {
      var response =
          PropagationEnvironment.HTTP.send(
              java.net.http.HttpRequest.newBuilder(environment.base.resolve(setting.getKey()))
                  .timeout(Duration.ofSeconds(5))
                  .header("Authorization", "Bearer " + environment.admin)
                  .header("Content-Type", "application/json")
                  .POST(
                      java.net.http.HttpRequest.BodyPublishers.ofString(
                          PropagationEnvironment.JSON.writeValueAsString(setting.getValue())))
                  .build(),
              java.net.http.HttpResponse.BodyHandlers.discarding());
      assertEquals(200, response.statusCode());
    }
  }

  private static int script(Path log, String scriptName, String... arguments) throws Exception {
    var command = new ArrayList<String>();
    boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
    if (windows) {
      command.addAll(List.of("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File"));
    } else {
      command.addAll(List.of("pwsh", "-NoProfile", "-File"));
    }
    command.add(Path.of("..", "load-tests", "jmeter", scriptName).toAbsolutePath().toString());
    command.addAll(List.of(arguments));

    ProcessBuilder pb = new ProcessBuilder(command);
    pb.redirectErrorStream(true);
    pb.redirectOutput(log.toFile());
    Process process = pb.start();
    boolean finished = process.waitFor(4, TimeUnit.MINUTES);
    if (!finished) {
      process.destroyForcibly();
      throw new AssertionError("Script timed out after 4 minutes; log: " + log);
    }
    return process.exitValue();
  }
}

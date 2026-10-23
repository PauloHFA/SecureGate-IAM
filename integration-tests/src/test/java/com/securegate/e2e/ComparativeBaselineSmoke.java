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
 * Opt-in comparative baseline test (Etapa 10C3): runs all five operational flows sequentially,
 * validates consolidated reports, verifies targets and enforces zero leak of credentials or JWTs.
 */
class ComparativeBaselineSmoke {
  @Test
  void measuresAllFiveScenariosComparativelyWithoutLeakingCredentials() throws Exception {
    String home = System.getProperty("securegate.jmeter.home", "");
    assertTrue(Files.isRegularFile(Path.of(home, "bin", "ApacheJMeter.jar")));

    int steadySeconds = Integer.getInteger("securegate.load.steady.seconds", 5);
    int windowSeconds = Integer.getInteger("securegate.load.window.seconds", 1);
    boolean omitIamPid = Boolean.getBoolean("securegate.load.omitIamPid");

    Path output =
        Path.of("target/comparative-capacity", UUID.randomUUID().toString()).toAbsolutePath();
    Path privateFiles = output.resolve("private");
    Files.createDirectories(privateFiles);
    Path admin = privateFiles.resolve("admin");

    try (var environment = new PropagationEnvironment()) {
      environment.start();
      configureTestRisk(environment);
      environment.enableAuditJourney();
      Files.writeString(admin, environment.admin);

      var comparativeArgs =
          new ArrayList<>(
              List.of(
                  "-JMeterHome",
                  home,
                  "-BaseUri",
                  environment.base.toString(),
                  "-AdminTokenFile",
                  admin.toString(),
                  "-OutputDirectory",
                  output.resolve("comparative").toString(),
                  "-Threads",
                  "2",
                  "-WarmupSeconds",
                  "3",
                  "-SteadySeconds",
                  Integer.toString(steadySeconds),
                  "-WindowSeconds",
                  Integer.toString(windowSeconds),
                  "-CooldownSeconds",
                  "3",
                  "-IamProcessId",
                  omitIamPid ? "0" : Long.toString(environment.iamProcessId()),
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
              output.resolve("comparative.log"),
              "Run-ComparativeBaseline.ps1",
              comparativeArgs.toArray(String[]::new)));

      Path comparativeSummaryJson = output.resolve("comparative/comparative-summary.json");
      Path comparativeSummaryCsv = output.resolve("comparative/comparative-summary.csv");
      Path comparativeReportHtml = output.resolve("comparative/comparative-report.html");
      assertTrue(Files.isRegularFile(comparativeSummaryJson));
      assertTrue(Files.isRegularFile(comparativeSummaryCsv));
      assertTrue(Files.isRegularFile(comparativeReportHtml));
      String htmlContent = Files.readString(comparativeReportHtml);
      assertTrue(htmlContent.contains("SecureGate IAM"));
      assertTrue(htmlContent.contains("Lei de Little"));

      var report = PropagationEnvironment.JSON.readTree(comparativeSummaryJson.toFile());
      var results = report.path("results");
      assertEquals(5, results.size());

      List<String> seenScenarios = new ArrayList<>();
      for (var result : results) {
        String scenario = result.path("scenario").asText();
        seenScenarios.add(scenario);
        assertEquals(0, result.path("failures").asInt());
        assertTrue(result.path("requests").asInt() > 0);
        assertTrue(result.path("effectiveRequestsPerSecond").asDouble() > 0);
        assertTrue(result.path("p50Ms").asDouble() > 0);
        assertTrue(result.path("p95Ms").asDouble() > 0);

        if (scenario.equals("revocation")) {
          assertEquals("revocation_propagation", result.path("secondaryType").asText());
          assertTrue(result.path("secondaryP95Ms").asDouble() > 0);
          assertTrue(result.path("steadyPropagationMeetsTarget").asBoolean());
        }
        if (scenario.equals("audit")) {
          assertEquals("audit_visibility", result.path("secondaryType").asText());
          assertTrue(result.path("secondaryP95Ms").asDouble() > 0);
          assertTrue(result.path("steadyPropagationMeetsTarget").asBoolean());
          assertTrue(result.path("secondaryQueriesP95Ms").asDouble() > 0);
        }

        Path scenarioDir = output.resolve("comparative/" + scenario);
        assertTrue(Files.isDirectory(scenarioDir));
        assertTrue(Files.isRegularFile(scenarioDir.resolve("summary.json")));
        assertTrue(Files.isRegularFile(scenarioDir.resolve("steady/samples.csv")));
      }

      assertEquals(
          List.of("issuance", "introspection", "resource", "revocation", "audit"), seenScenarios);

      var secrets = new ArrayList<String>();
      secrets.add(environment.admin);
      try (var paths = Files.walk(output.resolve("comparative"))) {
        for (Path path : paths.filter(Files::isRegularFile).toList()) {
          byte[] bytes = Files.readAllBytes(path);
          String content = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
          String utf16 = new String(bytes, java.nio.charset.StandardCharsets.UTF_16LE);
          for (String secret : secrets) {
            assertFalse(content.contains(secret), "Fixture credential leaked into " + path);
            assertFalse(utf16.contains(secret), "Fixture credential leaked into " + path);
          }
          assertFalse(
              java.util.regex.Pattern.compile("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")
                  .matcher(content)
                  .find(),
              "JWT leaked into " + path);
          assertFalse(
              java.util.regex.Pattern.compile("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")
                  .matcher(utf16)
                  .find(),
              "JWT leaked into " + path);
        }
      }
    } finally {
      Files.deleteIfExists(admin);
      Files.deleteIfExists(privateFiles);
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
      assertEquals(201, response.statusCode());
    }
  }

  private static int script(Path log, String name, String... arguments) throws Exception {
    boolean windows = System.getProperty("os.name").startsWith("Windows");
    var command =
        new ArrayList<>(
            List.of(
                System.getProperty("securegate.powershell", windows ? "powershell.exe" : "pwsh"),
                "-NoProfile",
                "-NonInteractive",
                "-File",
                Path.of("../load-tests/jmeter", name).toAbsolutePath().normalize().toString()));
    command.addAll(List.of(arguments));
    var process =
        new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
    try {
      assertTrue(process.waitFor(Duration.ofMinutes(8).toMillis(), TimeUnit.MILLISECONDS));
      return process.exitValue();
    } finally {
      if (process.isAlive()) {
        process.destroyForcibly();
        process.waitFor(10, TimeUnit.SECONDS);
      }
    }
  }
}

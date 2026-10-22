package com.securegate.e2e;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Opt-in token baseline: real JMeter phases, time windows and redacted artifacts. */
class TokenBaselineSmoke {
  @Test
  void measuresThreeRealPhasesWithoutLeakingSyntheticCredentials() throws Exception {
    String home = System.getProperty("securegate.jmeter.home", "");
    String scenario = System.getProperty("securegate.load.scenario", "issuance");
    int steadySeconds =
        Integer.getInteger("securegate.load.steady.seconds", scenario.equals("resource") ? 10 : 5);
    int windowSeconds = Integer.getInteger("securegate.load.window.seconds", 1);
    boolean omitIamPid = Boolean.getBoolean("securegate.load.omitIamPid");
    assertTrue(List.of("issuance", "introspection", "resource").contains(scenario));
    assertTrue(steadySeconds >= windowSeconds && windowSeconds > 0);
    assertTrue(Files.isRegularFile(Path.of(home, "bin", "ApacheJMeter.jar")));
    Path output = Path.of("target/load-capacity", UUID.randomUUID().toString()).toAbsolutePath();
    Path privateFiles = output.resolve("private");
    Files.createDirectories(privateFiles);
    Path admin = privateFiles.resolve("admin");
    Path mass = privateFiles.resolve("clients.csv");
    try (var environment = new PropagationEnvironment()) {
      environment.start();
      if (scenario.equals("resource")) {
        configureTestRisk(environment);
      }
      Files.writeString(admin, environment.admin);
      var prepareArgs =
          new ArrayList<>(
              List.of(
                  "-BaseUri",
                  environment.base.toString(),
                  "-AdminTokenFile",
                  admin.toString(),
                  "-OutputFile",
                  mass.toString(),
                  "-Prefix",
                  "capacity-smoke",
                  "-Count",
                  "2"));
      if (scenario.equals("resource")) {
        prepareArgs.add("-IncludeAccountPolicy");
      }
      assertEquals(
          0,
          script(
              output.resolve("prepare.log"),
              scenario.equals("resource") ? "New-HumanMass.ps1" : "New-ClientMass.ps1",
              prepareArgs.toArray(String[]::new)));
      assertEquals(
          0,
          script(
              output.resolve("baseline.log"),
              "Run-TokenIssuanceBaseline.ps1",
              "-JMeterHome",
              home,
              "-BaseUri",
              environment.base.toString(),
              "-MassFile",
              mass.toString(),
              "-OutputDirectory",
              output.resolve("baseline").toString(),
              "-Scenario",
              scenario,
              "-Threads",
              "2",
              "-WarmupSeconds",
              scenario.equals("resource") ? "8" : "3",
              "-SteadySeconds",
              Integer.toString(steadySeconds),
              "-WindowSeconds",
              Integer.toString(windowSeconds),
              "-CooldownSeconds",
              scenario.equals("resource") ? "8" : "3",
              "-IamProcessId",
              omitIamPid ? "0" : Long.toString(environment.iamProcessId()),
              "-DockerContainerIds",
              String.join(
                  ",",
                  environment.database.getContainerId(),
                  environment.redisContainer.getContainerId(),
                  environment.rabbit.getContainerId(),
                  environment.vault.getContainerId())));
      var report =
          PropagationEnvironment.JSON.readTree(output.resolve("baseline/summary.json").toFile());
      assertEquals(
          switch (scenario) {
            case "issuance" -> "client_credentials_token_issuance";
            case "introspection" -> "owned_active_token_introspection";
            default -> "protected_resource_validation";
          },
          report.path("scenario").asText());
      var phases = report.path("phases");
      assertEquals(3, phases.size());
      for (int i = 0; i < 3; i++) {
        assertEquals(0, phases.get(i).path("failures").asInt());
        assertTrue(phases.get(i).path("requests").asInt() > 0);
        assertTrue(phases.get(i).path("p95Ms").asDouble() > 0);
        assertEquals(
            scenario.equals("resource") ? (i == 1 ? 2 : 1) : 0,
            phases.get(i).path("setupSamples").asInt());
        if (omitIamPid) {
          assertTrue(phases.get(i).path("iamProcess").isNull());
        } else {
          assertTrue(phases.get(i).path("iamProcess").path("points").asInt() >= 2);
        }
        var containers = phases.get(i).path("containers");
        assertEquals(4, containers.size());
        for (var container : containers) {
          assertTrue(container.path("containerId").asText().matches("[a-f0-9]{12}"));
          assertTrue(container.path("points").asInt() >= 1);
          assertTrue(container.path("maxMemoryMiB").asDouble() > 0);
          assertTrue(container.path("maxCpuPercent").asDouble() >= 0);
        }
      }
      assertEquals("steady", phases.get(1).path("phase").asText());
      assertEquals(2, phases.get(1).path("threads").asInt());
      assertEquals(windowSeconds, report.path("windowSeconds").asInt());
      var windows = phases.get(1).path("windows");
      assertTrue(windows.size() > 0);
      assertTrue(windows.size() <= steadySeconds / windowSeconds);
      var timestamps =
          Files.readAllLines(output.resolve("baseline/steady/samples.csv")).stream()
              .skip(1)
              .filter(
                  line ->
                      !scenario.equals("resource")
                          || line.contains(",resource.validation.success,"))
              .mapToLong(line -> Long.parseLong(line.substring(0, line.indexOf(','))))
              .summaryStatistics();
      assertTrue(
          (long) windows.size() * windowSeconds * 1000
              <= timestamps.getMax() - timestamps.getMin());
      int countedRequests = 0;
      for (int i = 0; i < windows.size(); i++) {
        var window = windows.get(i);
        assertEquals(i, window.path("index").asInt());
        assertEquals(i * windowSeconds, window.path("offsetSeconds").asInt());
        assertEquals(windowSeconds, window.path("durationSeconds").asInt());
        assertEquals(0, window.path("failures").asInt());
        assertEquals(
            (double) window.path("requests").asInt() / windowSeconds,
            window.path("effectiveRequestsPerSecond").asDouble(),
            0.01);
        countedRequests += window.path("requests").asInt();
      }
      assertTrue(countedRequests > 0);
      assertTrue(countedRequests <= phases.get(1).path("requests").asInt());
      assertFalse(Files.exists(output.resolve("baseline/steady/private-token-mass.csv")));
      var secrets = new ArrayList<String>();
      secrets.add(environment.admin);
      for (String row : Files.readAllLines(mass).subList(1, 3)) {
        String[] fields = row.replace("\"", "").split(",");
        if (scenario.equals("resource")) {
          secrets.add(fields[0]);
          secrets.add(fields[1]);
          secrets.add(fields[2]);
          secrets.add(fields[4]);
          secrets.add(
              Base64.getEncoder()
                  .encodeToString(
                      (fields[3] + ":" + fields[4])
                          .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } else {
          secrets.add(fields[1]);
          secrets.add(
              Base64.getEncoder()
                  .encodeToString(
                      (fields[0] + ":" + fields[1])
                          .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
      }
      try (var paths = Files.walk(output.resolve("baseline"))) {
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
      Files.deleteIfExists(mass);
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
      assertTrue(process.waitFor(Duration.ofMinutes(4).toMillis(), TimeUnit.MILLISECONDS));
      return process.exitValue();
    } finally {
      if (process.isAlive()) {
        process.destroyForcibly();
        process.waitFor(10, TimeUnit.SECONDS);
      }
    }
  }
}

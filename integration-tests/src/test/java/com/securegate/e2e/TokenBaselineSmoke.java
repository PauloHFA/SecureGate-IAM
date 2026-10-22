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
    int steadySeconds = Integer.getInteger("securegate.load.steady.seconds", 5);
    int windowSeconds = Integer.getInteger("securegate.load.window.seconds", 1);
    assertTrue(steadySeconds >= windowSeconds && windowSeconds > 0);
    assertTrue(Files.isRegularFile(Path.of(home, "bin", "ApacheJMeter.jar")));
    Path output = Path.of("target/load-capacity", UUID.randomUUID().toString()).toAbsolutePath();
    Path privateFiles = output.resolve("private");
    Files.createDirectories(privateFiles);
    Path admin = privateFiles.resolve("admin");
    Path mass = privateFiles.resolve("clients.csv");
    try (var environment = new PropagationEnvironment()) {
      environment.start();
      Files.writeString(admin, environment.admin);
      assertEquals(
          0,
          script(
              output.resolve("prepare.log"),
              "New-ClientMass.ps1",
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
              Long.toString(environment.iamProcessId())));
      var report =
          PropagationEnvironment.JSON.readTree(output.resolve("baseline/summary.json").toFile());
      assertEquals("client_credentials_token_issuance", report.path("scenario").asText());
      var phases = report.path("phases");
      assertEquals(3, phases.size());
      for (int i = 0; i < 3; i++) {
        assertEquals(0, phases.get(i).path("failures").asInt());
        assertTrue(phases.get(i).path("requests").asInt() > 0);
        assertTrue(phases.get(i).path("p95Ms").asDouble() > 0);
        assertTrue(phases.get(i).path("iamProcess").path("points").asInt() >= 2);
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
      var secrets = new ArrayList<String>();
      secrets.add(environment.admin);
      for (String row : Files.readAllLines(mass).subList(1, 3)) {
        String[] fields = row.replace("\"", "").split(",");
        secrets.add(fields[1]);
        secrets.add(
            Base64.getEncoder()
                .encodeToString(
                    (fields[0] + ":" + fields[1])
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
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

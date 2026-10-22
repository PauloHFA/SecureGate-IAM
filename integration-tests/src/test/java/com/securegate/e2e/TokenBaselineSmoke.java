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

/** Opt-in 10C1 baseline: real JMeter phases, contract, resource samples and redacted artifacts. */
class TokenBaselineSmoke {
  @Test
  void measuresThreeRealPhasesWithoutLeakingSyntheticCredentials() throws Exception {
    String home = System.getProperty("securegate.jmeter.home", "");
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
              "5",
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

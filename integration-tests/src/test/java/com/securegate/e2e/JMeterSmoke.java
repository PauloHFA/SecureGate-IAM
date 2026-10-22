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

/** Opt-in: -Dit.test=JMeterSmoke -Dsecuregate.jmeter.home=...; never a capacity benchmark. */
class JMeterSmoke {
  @Test
  void realPlanCreatesSyntheticClientsChecksBothContractsAndRejectsBrokenMass() throws Exception {
    String home = System.getProperty("securegate.jmeter.home", "");
    assertTrue(
        Files.isRegularFile(Path.of(home, "bin", "ApacheJMeter.jar")),
        "Set securegate.jmeter.home");
    Path output = Path.of("target/load-smoke", UUID.randomUUID().toString()).toAbsolutePath();
    Path privateFiles = output.resolve("private");
    Files.createDirectories(privateFiles);
    Path admin = privateFiles.resolve("admin");
    Path mass = privateFiles.resolve("clients.csv");
    Path broken = privateFiles.resolve("broken.csv");
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
              "load-smoke",
              "-Count",
              "2"));
      var original = Files.readAllLines(mass);
      assertEquals(3, original.size());
      assertEquals(0, run(output, "valid", home, environment, mass));
      assertTrue(Files.isRegularFile(output.resolve("valid/dashboard/index.html")));
      var summary = Files.readString(output.resolve("valid/summary.csv"));
      assertTrue(summary.contains("\"token.success\",\"4\",\"0\""));
      assertTrue(summary.contains("\"token.invalid_client.expected\",\"4\",\"0\""));
      // Changing all valid credentials must fail the runner, even though JMeter exits normally.
      List<String> damaged = new ArrayList<>();
      damaged.add(original.getFirst());
      List<String> sensitive = new ArrayList<>();
      sensitive.add(environment.admin);
      for (String row : original.subList(1, original.size())) {
        String[] fields = row.split(",");
        String client = fields[0].replace("\"", "");
        String secret = fields[1].replace("\"", "");
        sensitive.add(secret);
        sensitive.add(
            Base64.getEncoder()
                .encodeToString(
                    (client + ":" + secret).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        fields[1] = "\"deliberately-invalid-fixture\"";
        damaged.add(String.join(",", fields));
      }
      Files.write(broken, damaged);
      assertNotEquals(0, run(output, "broken", home, environment, broken));
      assertTrue(
          Files.readString(output.resolve("broken/summary.csv"))
              .contains("\"token.success\",\"4\",\"4\""));
      try (var paths = Files.walk(output)) {
        for (Path path :
            paths.filter(Files::isRegularFile).filter(p -> !p.startsWith(privateFiles)).toList()) {
          if (!path.toString().matches(".*\\.(csv|log|html|json|js)$")) {
            continue;
          }
          String artifact = Files.readString(path);
          for (String value : sensitive) {
            assertFalse(artifact.contains(value), "Sensitive fixture leaked into a run artifact");
          }
          assertFalse(
              java.util.regex.Pattern.compile("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")
                  .matcher(artifact)
                  .find(),
              "JWT leaked into a run artifact");
        }
      }
      System.out.println(
          "JMeter smoke: 8 samples passed; broken mass rejected; artifacts checked for fixture secrets.");
    } finally {
      Files.deleteIfExists(admin);
      Files.deleteIfExists(mass);
      Files.deleteIfExists(broken);
      Files.deleteIfExists(privateFiles);
    }
  }

  private int run(
      Path output, String name, String home, PropagationEnvironment environment, Path mass)
      throws Exception {
    return script(
        output.resolve(name + ".log"),
        "Run-ClientCredentials.ps1",
        "-JMeterHome",
        home,
        "-BaseUri",
        environment.base.toString(),
        "-MassFile",
        mass.toString(),
        "-OutputDirectory",
        output.resolve(name).toString());
  }

  private int script(Path log, String name, String... arguments) throws Exception {
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
      assertTrue(
          process.waitFor(Duration.ofMinutes(3).toMillis(), TimeUnit.MILLISECONDS),
          "Load script timed out; inspect target/load-smoke");
      return process.exitValue();
    } finally {
      if (process.isAlive()) {
        process.destroyForcibly();
        process.waitFor(10, TimeUnit.SECONDS);
      }
    }
  }
}

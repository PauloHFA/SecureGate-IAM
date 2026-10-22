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

/** Opt-in: -Dit.test=JMeterHumanSmoke -Dsecuregate.jmeter.home=...; never a capacity benchmark. */
class JMeterHumanSmoke {
  @Test
  void realHumanJourneyChecksPkceReplayIsolationAndWrongVerifier() throws Exception {
    String home = System.getProperty("securegate.jmeter.home", "");
    assertTrue(
        Files.isRegularFile(Path.of(home, "bin", "ApacheJMeter.jar")),
        "Set securegate.jmeter.home");
    Path output = Path.of("target/human-pkce-smoke", UUID.randomUUID().toString()).toAbsolutePath();
    Path privateFiles = output.resolve("private");
    Files.createDirectories(privateFiles);
    Path admin = privateFiles.resolve("admin");
    Path mass = privateFiles.resolve("clients.csv");
    try (var environment = new PropagationEnvironment()) {
      environment.start();
      configureTestRisk(environment);
      Files.writeString(admin, environment.admin);
      assertEquals(
          0,
          script(
              output.resolve("prepare.log"),
              "New-HumanMass.ps1",
              "-BaseUri",
              environment.base.toString(),
              "-AdminTokenFile",
              admin.toString(),
              "-OutputFile",
              mass.toString(),
              "-Prefix",
              "load-human",
              "-Count",
              "2"));
      var original = Files.readAllLines(mass);
      assertEquals(3, original.size());
      assertEquals(0, run(output, "valid", home, environment, mass, false));
      assertTrue(Files.isRegularFile(output.resolve("valid/dashboard/index.html")));
      var summary = Files.readString(output.resolve("valid/summary.csv"));
      assertTrue(summary.contains("\"pkce.journey.success\",\"2\",\"0\""));
      assertTrue(summary.contains("\"pkce.code_replay.expected\",\"2\",\"0\""));
      List<String> sensitive = new ArrayList<>();
      sensitive.add(environment.admin);
      for (String row : original.subList(1, original.size())) {
        String[] fields = row.split(",");
        String client = fields[3].replace("\"", "");
        String secret = fields[4].replace("\"", "");
        sensitive.add(fields[0].replace("\"", ""));
        sensitive.add(fields[1].replace("\"", ""));
        sensitive.add(secret);
        sensitive.add(
            Base64.getEncoder()
                .encodeToString(
                    (client + ":" + secret).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
      }
      assertNotEquals(0, run(output, "wrong-verifier", home, environment, mass, true));
      var failed = Files.readAllLines(output.resolve("wrong-verifier/samples.csv"));
      var failedJourneys =
          failed.stream().filter(line -> line.contains(",pkce.journey.success,")).toList();
      assertEquals(2, failedJourneys.size());
      assertTrue(
          failedJourneys.stream().allMatch(line -> line.contains(",400,")),
          "Wrong verifier must reach the real PKCE rejection");
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
          "JMeter human smoke: 2 PKCE journeys and 2 replay rejections passed; wrong verifier rejected; artifacts sanitized.");
    } finally {
      Files.deleteIfExists(admin);
      Files.deleteIfExists(mass);
      Files.deleteIfExists(privateFiles);
    }
  }

  private void configureTestRisk(PropagationEnvironment environment) throws Exception {
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

  private int run(
      Path output,
      String name,
      String home,
      PropagationEnvironment environment,
      Path mass,
      boolean wrong)
      throws Exception {
    var arguments =
        new ArrayList<>(
            List.of(
                "-JMeterHome",
                home,
                "-BaseUri",
                environment.base.toString(),
                "-MassFile",
                mass.toString(),
                "-OutputDirectory",
                output.resolve(name).toString()));
    if (wrong) {
      arguments.add("-WrongVerifier");
    }
    return script(
        output.resolve(name + ".log"), "Run-HumanPkce.ps1", arguments.toArray(String[]::new));
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
          "Load script timed out; inspect target/human-pkce-smoke");
      return process.exitValue();
    } finally {
      if (process.isAlive()) {
        process.destroyForcibly();
        process.waitFor(10, TimeUnit.SECONDS);
      }
    }
  }
}

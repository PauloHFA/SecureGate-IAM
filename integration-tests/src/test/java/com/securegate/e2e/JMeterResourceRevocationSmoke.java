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

/**
 * Opt-in: -Dit.test=JMeterResourceRevocationSmoke -Dsecuregate.jmeter.home=...; never a capacity
 * benchmark.
 */
class JMeterResourceRevocationSmoke {
  @Test
  void realResourceAccessRevocationProjectionAndMissingRevocationControl() throws Exception {
    String home = System.getProperty("securegate.jmeter.home", "");
    assertTrue(
        Files.isRegularFile(Path.of(home, "bin", "ApacheJMeter.jar")),
        "Set securegate.jmeter.home");
    Path output =
        Path.of("target/resource-revocation-smoke", UUID.randomUUID().toString()).toAbsolutePath();
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
              "load-resource",
              "-Count",
              "2",
              "-IncludeAccountPolicy"));
      var original = Files.readAllLines(mass);
      assertEquals(3, original.size());
      assertEquals(0, run(output, "valid", home, environment, mass, false));
      assertTrue(Files.isRegularFile(output.resolve("valid/dashboard/index.html")));
      var summary = Files.readString(output.resolve("valid/summary.csv"));
      assertTrue(summary.contains("\"pkce.journey.success\",\"2\",\"0\""));
      assertTrue(summary.contains("\"resource.revoked.expected\",\"2\",\"0\""));
      List<String> sensitive = new ArrayList<>();
      sensitive.add(environment.admin);
      for (String row : original.subList(1, original.size())) {
        String[] fields = row.split(",");
        String client = fields[3].replace("\"", "");
        String secret = fields[4].replace("\"", "");
        sensitive.add(fields[0].replace("\"", ""));
        sensitive.add(fields[1].replace("\"", ""));
        sensitive.add(secret);
        sensitive.add(fields[2].replace("\"", ""));
        sensitive.add(
            Base64.getEncoder()
                .encodeToString(
                    (client + ":" + secret).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
      }
      // HTTP refusal alone also follows the local ledger. Check asynchronous publication and
      // projection independently, outside JMeter timing, without declaring a propagation SLA.
      PropagationEnvironment.await(
          "revocation events published and projected",
          Duration.ofSeconds(45),
          () -> {
            var events =
                environment.jdbc.queryForList(
                    "SELECT event_id FROM iam_audit.outbox_events WHERE event_type='token.revoked' AND published_at IS NOT NULL",
                    UUID.class);
            return events.size() == 2
                && events.stream()
                    .allMatch(
                        id ->
                            Boolean.TRUE.equals(
                                environment.redis.hasKey("securegate:{revocation}:event:" + id)));
          });
      assertNotEquals(0, run(output, "skip-revocation", home, environment, mass, true));
      var failed = Files.readAllLines(output.resolve("skip-revocation/samples.csv"));
      var failedResources =
          failed.stream().filter(line -> line.contains(",resource.revoked.expected,")).toList();
      assertEquals(2, failedResources.size());
      assertTrue(
          failedResources.stream()
              .allMatch(line -> line.contains(",200,resource_contract_failed,")),
          "An unrevoked token must still access the real resource and fail the expected refusal");
      assertEquals(
          2,
          environment.jdbc.queryForObject(
              "SELECT count(*) FROM iam_audit.outbox_events WHERE event_type='token.revoked'",
              Integer.class));
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
          "JMeter resource smoke: 10 samples passed; publication/projection checked; missing revocation detected; artifacts sanitized.");
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
                output.resolve(name).toString(),
                "-Threads",
                "2"));
    if (wrong) {
      arguments.add("-SkipRevocation");
    }
    return script(
        output.resolve(name + ".log"),
        "Run-ResourceRevocation.ps1",
        arguments.toArray(String[]::new));
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
          "Load script timed out; inspect target/resource-revocation-smoke");
      return process.exitValue();
    } finally {
      if (process.isAlive()) {
        process.destroyForcibly();
        process.waitFor(10, TimeUnit.SECONDS);
      }
    }
  }
}

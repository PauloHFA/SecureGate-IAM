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

/** Opt-in only: real API -> Outbox -> RabbitMQ -> restricted audit store -> query API. */
class JMeterAuditSmoke {
  @Test
  void realEventsBecomeVisibleThroughCursorAndAbsentCorrelationFails() throws Exception {
    String home = System.getProperty("securegate.jmeter.home", "");
    assertTrue(
        Files.isRegularFile(Path.of(home, "bin", "ApacheJMeter.jar")),
        "Set securegate.jmeter.home");
    Path output = Path.of("target/audit-smoke", UUID.randomUUID().toString()).toAbsolutePath();
    Path privateFiles = output.resolve("private");
    Files.createDirectories(privateFiles);
    Path admin = privateFiles.resolve("admin");
    try (var environment = new PropagationEnvironment()) {
      environment.start();
      environment.enableAuditJourney();
      Files.writeString(admin, environment.admin);
      assertEquals(0, run(output, "valid", home, environment, admin, false));
      assertTrue(Files.isRegularFile(output.resolve("valid/dashboard/index.html")));
      String summary = Files.readString(output.resolve("valid/summary.csv"));
      for (String label :
          List.of(
              "audit.produce.success",
              "audit.visible.expected",
              "audit.cursor.success",
              "audit.unauthorized.expected")) {
        assertTrue(summary.contains("\"" + label + "\",\"2\",\"0\""));
      }
      assertNotEquals(0, run(output, "wrong-correlation", home, environment, admin, true));
      var failed = Files.readAllLines(output.resolve("wrong-correlation/samples.csv"));
      var visible =
          failed.stream().filter(line -> line.contains(",audit.visible.expected,")).toList();
      assertEquals(2, visible.size());
      assertTrue(
          visible.stream().allMatch(line -> line.contains(",200,audit_contract_failed,")),
          "Wrong correlation must reach the real query API and fail on empty results");
      assertTrue(
          Files.readString(output.resolve("wrong-correlation/summary.csv"))
              .contains("\"audit.unauthorized.expected\",\"2\",\"0\""));
      // Outside the timed plan: both normal and control executions created real facts.
      PropagationEnvironment.await(
          "eight audit events persisted and published",
          Duration.ofSeconds(30),
          () ->
              environment.jdbc.queryForObject(
                          "SELECT count(*) FROM iam_audit.event_log WHERE event_type='identity.user.created'",
                          Integer.class)
                      == 8
                  && environment.jdbc.queryForObject(
                          "SELECT count(*) FROM iam_audit.outbox_events WHERE event_type='identity.user.created' AND published_at IS NOT NULL",
                          Integer.class)
                      == 8);
      assertEquals(
          8,
          environment.jdbc.queryForObject(
              "SELECT count(*) FROM iam_audit.event_log e JOIN iam_audit.outbox_events o ON e.id=o.event_id WHERE e.event_type='identity.user.created' AND e.payload=o.payload",
              Integer.class));
      List<String> sensitive = new ArrayList<>();
      sensitive.add(environment.admin);
      sensitive.add(environment.database.getPassword());
      sensitive.addAll(
          environment.jdbc.queryForList("SELECT email FROM iam_identity.users", String.class));
      sensitive.addAll(
          environment.jdbc.queryForList("SELECT id::text FROM iam_identity.users", String.class));
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
              java.util.regex.Pattern.compile(
                      "eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+|Audit![A-Za-z0-9_-]{32}")
                  .matcher(artifact)
                  .find(),
              "Credential pattern leaked into a run artifact");
        }
      }
      System.out.println(
          "JMeter audit smoke: 8 samples passed; cursor and unauthorized contracts checked; absent correlation detected; 8 envelopes matched; artifacts sanitized.");
    } finally {
      Files.deleteIfExists(admin);
      Files.deleteIfExists(privateFiles);
    }
  }

  private int run(
      Path output,
      String name,
      String home,
      PropagationEnvironment environment,
      Path admin,
      boolean wrong)
      throws Exception {
    var arguments =
        new ArrayList<>(
            List.of(
                "-JMeterHome",
                home,
                "-BaseUri",
                environment.base.toString(),
                "-AdminTokenFile",
                admin.toString(),
                "-OutputDirectory",
                output.resolve(name).toString(),
                "-Threads",
                "2"));
    if (wrong) {
      arguments.add("-WrongCorrelation");
    }
    return script(output.resolve(name + ".log"), "Run-Audit.ps1", arguments.toArray(String[]::new));
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
          "Load script timed out; inspect target/audit-smoke");
      return process.exitValue();
    } finally {
      if (process.isAlive()) {
        process.destroyForcibly();
        process.waitFor(10, TimeUnit.SECONDS);
      }
    }
  }
}

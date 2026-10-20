package com.securegate.iam;

import static org.junit.jupiter.api.Assertions.*;

import com.securegate.events.SecurityEvent;
import com.securegate.iam.audit.application.port.AuditEventStore;
import com.securegate.iam.audit.infrastructure.AuditDatabaseConnection;
import com.securegate.iam.audit.infrastructure.AuditPartitionMaintenance;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuditPrivilegesIT {
  private static final String INGEST = "securegate_audit_ingest_login";
  private static final String MAINTENANCE = "securegate_audit_maintenance_login";
  private static final String INGEST_PASSWORD = secret();
  private static final String MAINTENANCE_PASSWORD = secret();
  private static Path credentials;
  private static String url;

  @Container
  static final PostgreSQLContainer<?> DATABASE =
      new PostgreSQLContainer<>("postgres:16.15-alpine")
          .withDatabaseName("audit_privileges_test")
          .withUsername("audit_owner")
          .withPassword(UUID.randomUUID().toString())
          .withEnv("AUDIT_INGEST_PASSWORD", INGEST_PASSWORD)
          .withEnv("AUDIT_MAINTENANCE_PASSWORD", MAINTENANCE_PASSWORD);

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) throws Exception {
    url = DATABASE.getJdbcUrl().split("\\?")[0];
    Flyway.configure()
        .dataSource(url, DATABASE.getUsername(), DATABASE.getPassword())
        .load()
        .migrate();
    provision();
    credentials = Files.createTempDirectory("securegate-audit-roles-");
    Files.writeString(credentials.resolve("ingest"), INGEST_PASSWORD);
    Files.writeString(credentials.resolve("maintenance"), MAINTENANCE_PASSWORD);
    registry.add("spring.datasource.url", () -> url);
    registry.add("spring.datasource.username", DATABASE::getUsername);
    registry.add("spring.datasource.password", DATABASE::getPassword);
    registry.add("iam.audit.ingestion.enabled", () -> true);
    registry.add("iam.audit.partitions.enabled", () -> true);
    registry.add("iam.audit.ingestion.database-url", () -> url);
    registry.add("iam.audit.partitions.database-url", () -> url);
    registry.add("iam.audit.ingestion.username", () -> INGEST);
    registry.add("iam.audit.partitions.username", () -> MAINTENANCE);
    registry.add(
        "iam.audit.ingestion.password-file", () -> credentials.resolve("ingest").toString());
    registry.add(
        "iam.audit.partitions.password-file", () -> credentials.resolve("maintenance").toString());
  }

  private static void provision() throws Exception {
    try (var connection = owner()) {
      try (var setting = connection.prepareStatement("SELECT set_config(?, ?, false)")) {
        for (var entry :
            Map.of(
                    "securegate.securegate_audit_ingest_password",
                    INGEST_PASSWORD,
                    "securegate.securegate_audit_maintenance_password",
                    MAINTENANCE_PASSWORD)
                .entrySet()) {
          setting.setString(1, entry.getKey());
          setting.setString(2, entry.getValue());
          setting.execute();
        }
      }
      try (var statement = connection.createStatement()) {
        statement.execute(Files.readString(Path.of("../infra/postgres/audit-privileges.sql")));
      }
    }
  }

  @AfterAll
  static void cleanup() throws Exception {
    if (credentials != null) {
      Files.deleteIfExists(credentials.resolve("ingest"));
      Files.deleteIfExists(credentials.resolve("maintenance"));
      Files.deleteIfExists(credentials);
    }
  }

  @Autowired JdbcTemplate main;
  @Autowired AuditEventStore store;
  @Autowired AuditPartitionMaintenance worker;

  @Autowired
  @Qualifier("auditIngestionConnection")
  AuditDatabaseConnection ingest;

  @Autowired
  @Qualifier("auditMaintenanceConnection")
  AuditDatabaseConnection maintenance;

  @Test
  void poolsAndTransactionsUseIndependentRealLoginsWithoutReplacingMainDataSource() {
    assertEquals(DATABASE.getUsername(), main.queryForObject("SELECT current_user", String.class));
    assertEquals(INGEST, ingest.jdbc().queryForObject("SELECT current_user", String.class));
    assertEquals(
        MAINTENANCE, maintenance.jdbc().queryForObject("SELECT current_user", String.class));
    assertEquals(
        1,
        main.queryForObject(
            "SELECT count(*) FROM flyway_schema_history WHERE version='14' AND success",
            Integer.class));
    var event = event();
    assertEquals(AuditEventStore.Result.APPENDED, store.append(event));
    assertEquals(AuditEventStore.Result.DUPLICATE, store.append(event));
    assertEquals(
        1,
        ingest
            .jdbc()
            .queryForObject(
                "SELECT count(*) FROM iam_audit.event_log WHERE id=?",
                Integer.class,
                event.eventId()));
    assertEquals(
        1,
        main.queryForObject(
            "SELECT count(*) FROM iam_audit.event_receipts WHERE id=?",
            Integer.class,
            event.eventId()));
  }

  @Test
  void ingestionCannotMutateReceiptsLegacyAuditOrDdlEvenWithSetRole() throws Exception {
    assertDenied(ingest.jdbc(), "UPDATE iam_audit.event_log SET actor='tampered'");
    assertDenied(ingest.jdbc(), "DELETE FROM iam_audit.event_log");
    assertDenied(ingest.jdbc(), "TRUNCATE iam_audit.event_log");
    assertDenied(
        ingest.jdbc(),
        "INSERT INTO iam_audit.event_receipts(id,occurred_at,fingerprint) VALUES (gen_random_uuid(),now(),sha256('invalid'::bytea))");
    assertDenied(ingest.jdbc(), "SELECT * FROM iam_audit.event_receipts");
    assertDenied(ingest.jdbc(), "SELECT * FROM iam_audit.security_events");
    assertDenied(ingest.jdbc(), "SELECT * FROM iam_audit.outbox_events");
    assertDenied(ingest.jdbc(), "CREATE TABLE iam_audit.ingest_escape(id int)");
    assertDenied(ingest.jdbc(), "ALTER TABLE iam_audit.event_log DISABLE TRIGGER ALL");
    assertDenied(ingest.jdbc(), "SELECT iam_audit.maintain_event_partitions(3)");
    assertDenied(ingest.jdbc(), "SELECT iam_audit.ensure_event_partitions(DATE '2040-01-01',1)");
    try (var connection = login(INGEST, INGEST_PASSWORD);
        var statement = connection.createStatement()) {
      assertThrows(
          SQLException.class, () -> statement.execute("SET ROLE securegate_audit_maintenance"));
      assertThrows(SQLException.class, () -> statement.execute("SET ROLE audit_owner"));
      statement.execute("SET ROLE securegate_audit_ingest");
      assertThrows(
          SQLException.class,
          () -> statement.execute("ALTER TABLE iam_audit.event_log DISABLE TRIGGER ALL"));
    }
  }

  @Test
  void maintenanceCanOnlyUseTheBoundedEntryPointAndNeverWriteAuditData() {
    assertNotNull(
        maintenance
            .jdbc()
            .queryForObject("SELECT iam_audit.maintain_event_partitions(3)", Integer.class));
    assertThrows(
        DataAccessException.class,
        () ->
            maintenance
                .jdbc()
                .queryForObject("SELECT iam_audit.maintain_event_partitions(13)", Integer.class));
    assertDenied(
        maintenance.jdbc(), "SELECT iam_audit.ensure_event_partitions(DATE '2040-01-01',1)");
    assertDenied(maintenance.jdbc(), "SELECT * FROM iam_audit.event_log");
    assertDenied(maintenance.jdbc(), "INSERT INTO iam_audit.event_log DEFAULT VALUES");
    assertDenied(maintenance.jdbc(), "CREATE TABLE iam_audit.maintenance_escape(id int)");
    assertDenied(maintenance.jdbc(), "ALTER TABLE iam_audit.event_log DISABLE TRIGGER ALL");
    assertDenied(maintenance.jdbc(), "TRUNCATE iam_audit.event_receipts CASCADE");
  }

  @Test
  void newlyCreatedPartitionsKeepParentAccessButNeverGrantDirectChildAccess() {
    int count =
        maintenance
            .jdbc()
            .queryForObject("SELECT iam_audit.maintain_event_partitions(12)", Integer.class);
    assertTrue(count >= 0);
    var month =
        main.queryForObject(
                "SELECT (date_trunc('month', clock_timestamp() AT TIME ZONE 'UTC') + interval '12 months')::date",
                java.sql.Date.class)
            .toLocalDate();
    var time = month.atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
    var event =
        new SecurityEvent(
            UUID.randomUUID(),
            "audit.test",
            1,
            time,
            "iam-core",
            "synthetic",
            "synthetic",
            Map.of("resource", "test:resource"),
            Map.of());
    assertEquals(AuditEventStore.Result.APPENDED, store.append(event));
    assertEquals(
        1,
        ingest
            .jdbc()
            .queryForObject(
                "SELECT count(*) FROM iam_audit.event_log WHERE id=?",
                Integer.class,
                event.eventId()));
    String child =
        "iam_audit.event_log_"
            + month.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM"));
    assertDenied(ingest.jdbc(), "SELECT * FROM " + child);
    assertDenied(ingest.jdbc(), "TRUNCATE " + child);
    assertDenied(maintenance.jdbc(), "DROP TABLE " + child);
  }

  @Test
  void lockContentionTimesOutAndMaintenanceRecoversWithoutRestart() throws Exception {
    try (var connection = owner();
        var statement = connection.createStatement()) {
      connection.setAutoCommit(false);
      statement.execute("SELECT pg_advisory_xact_lock(81263,8)");
      long started = System.nanoTime();
      assertThrows(
          DataAccessException.class,
          () ->
              maintenance
                  .jdbc()
                  .queryForObject("SELECT iam_audit.maintain_event_partitions(3)", Integer.class));
      assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 5000);
      connection.rollback();
    }
    assertNotNull(
        maintenance
            .jdbc()
            .queryForObject("SELECT iam_audit.maintain_event_partitions(3)", Integer.class));
    worker.runOnce();
  }

  @Test
  void statementTimeoutAndDefaultLimitsApplyToAuthenticatedSessions() throws Exception {
    try (var connection = login(INGEST, INGEST_PASSWORD);
        var statement = connection.createStatement()) {
      try (var rows = statement.executeQuery("SHOW statement_timeout")) {
        rows.next();
        assertEquals("5s", rows.getString(1));
      }
      try (var rows = statement.executeQuery("SHOW lock_timeout")) {
        rows.next();
        assertEquals("1s", rows.getString(1));
      }
      try (var rows = statement.executeQuery("SHOW idle_in_transaction_session_timeout")) {
        rows.next();
        assertEquals("10s", rows.getString(1));
      }
      long started = System.nanoTime();
      var failure =
          assertThrows(SQLException.class, () -> statement.execute("SELECT pg_sleep(10)"));
      assertEquals("57014", failure.getSQLState());
      assertTrue(Duration.ofNanos(System.nanoTime() - started).toSeconds() < 8);
      assertTrue(statement.execute("SELECT 1"));
    }
  }

  @Test
  void reprovisioningPreservesExistingDataAndTheSameCredentials() throws Exception {
    var event = event();
    assertEquals(AuditEventStore.Result.APPENDED, store.append(event));
    // Exercise the exact psql prefix used by the local PowerShell script, with env-only secrets.
    String script = Files.readString(Path.of("../infra/scripts/Provision-AuditDatabase.ps1"));
    String prefix = script.split("\\$prefix = @'\\R", 2)[1].split("\\R'@", 2)[0];
    Path sql = Files.createTempFile("securegate-audit-provision-", ".sql");
    try {
      Files.writeString(
          sql, prefix + "\n" + Files.readString(Path.of("../infra/postgres/audit-privileges.sql")));
      DATABASE.copyFileToContainer(
          org.testcontainers.utility.MountableFile.forHostPath(sql), "/tmp/audit-provision.sql");
      var result =
          DATABASE.execInContainer(
              "psql",
              "-X",
              "-q",
              "-v",
              "ON_ERROR_STOP=1",
              "-U",
              DATABASE.getUsername(),
              "-d",
              DATABASE.getDatabaseName(),
              "-f",
              "/tmp/audit-provision.sql");
      assertEquals(0, result.getExitCode(), "psql provisioning must succeed");
      assertFalse((result.getStdout() + result.getStderr()).contains(INGEST_PASSWORD));
      assertFalse((result.getStdout() + result.getStderr()).contains(MAINTENANCE_PASSWORD));
    } finally {
      Files.deleteIfExists(sql);
    }
    try (var connection = login(INGEST, INGEST_PASSWORD)) {
      assertTrue(connection.isValid(2));
    }
    assertEquals(AuditEventStore.Result.DUPLICATE, store.append(event));
  }

  private static Connection owner() throws SQLException {
    return DriverManager.getConnection(url, DATABASE.getUsername(), DATABASE.getPassword());
  }

  private static Connection login(String username, String password) throws SQLException {
    return DriverManager.getConnection(url, username, password);
  }

  private static SecurityEvent event() {
    return new SecurityEvent(
        UUID.randomUUID(),
        "audit.test",
        1,
        Instant.now().truncatedTo(ChronoUnit.MICROS),
        "iam-core",
        "synthetic",
        "synthetic",
        Map.of("resource", "test:resource"),
        Map.of());
  }

  private static void assertDenied(JdbcTemplate jdbc, String sql) {
    var failure = assertThrows(DataAccessException.class, () -> jdbc.execute(sql));
    Throwable cause = failure;
    while (cause.getCause() != null) cause = cause.getCause();
    assertInstanceOf(SQLException.class, cause);
    assertEquals("42501", ((SQLException) cause).getSQLState());
  }

  private static String secret() {
    byte[] bytes = new byte[32];
    new java.security.SecureRandom().nextBytes(bytes);
    return java.util.HexFormat.of().formatHex(bytes);
  }
}

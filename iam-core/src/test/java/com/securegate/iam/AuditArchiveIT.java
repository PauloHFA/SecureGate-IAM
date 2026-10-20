package com.securegate.iam;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securegate.events.SecurityEvent;
import com.securegate.iam.audit.application.ArchivedAuditMonthException;
import com.securegate.iam.audit.application.port.AuditEventStore;
import com.securegate.iam.audit.infrastructure.JdbcAuditEventStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class AuditArchiveIT {
  private static final String INGEST_PASSWORD = secret();
  private static final String MAINTENANCE_PASSWORD = secret();

  @Container
  static final PostgreSQLContainer<?> DATABASE =
      new PostgreSQLContainer<>("postgres:16.15-alpine")
          .withDatabaseName("audit_archive")
          .withUsername("audit_owner")
          .withPassword(secret());

  private static JdbcTemplate owner;
  private static JdbcAuditEventStore store;
  private static DataSourceTransactionManager manager;
  private static LocalDate currentMonth;

  @BeforeAll
  static void prepare() throws Exception {
    Flyway.configure()
        .dataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword())
        .load()
        .migrate();
    var source =
        new DriverManagerDataSource(
            DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
    owner = new JdbcTemplate(source);
    manager = new DataSourceTransactionManager(source);
    currentMonth =
        owner.queryForObject(
            "SELECT date_trunc('month',clock_timestamp() AT TIME ZONE 'UTC')::date",
            LocalDate.class);
    try (var connection = source.getConnection()) {
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
    var ingest =
        new DriverManagerDataSource(
            DATABASE.getJdbcUrl(), "securegate_audit_ingest_login", INGEST_PASSWORD);
    store =
        new JdbcAuditEventStore(
            new JdbcTemplate(ingest),
            new ObjectMapper().findAndRegisterModules(),
            new DataSourceTransactionManager(ingest));
  }

  @Test
  void archivePreservesRowsReceiptsAndIndependentGuardsAfterDetach() {
    LocalDate month = currentMonth.minusMonths(12);
    provision(month);
    var original = event(month, "original");
    store.append(original);
    assertEquals(1, archive(month));
    assertEquals(1, archive(month));
    assertFalse(attached(month));
    assertEquals(
        0,
        owner.queryForObject(
            "SELECT count(*) FROM iam_audit.event_log WHERE id=?",
            Integer.class,
            original.eventId()));
    String table = archivedTable(month);
    assertEquals(1, owner.queryForObject("SELECT count(*) FROM " + table, Integer.class));
    assertEquals(1, receipts(original));
    for (String sql :
        List.of(
            "UPDATE " + table + " SET actor='changed'",
            "DELETE FROM " + table,
            "TRUNCATE " + table,
            "INSERT INTO " + table + " SELECT * FROM " + table)) {
      assertThrows(DataAccessException.class, () -> owner.update(sql));
    }
    assertThrows(DataAccessException.class, () -> provision(month));
    assertThrows(
        DataAccessException.class,
        () ->
            owner.update(
                "DELETE FROM iam_audit.archived_months WHERE month=?", Date.valueOf(month)));
    assertThrows(
        DataAccessException.class, () -> owner.execute("TRUNCATE iam_audit.archived_months"));
    assertEquals(1, owner.queryForObject("SELECT count(*) FROM " + table, Integer.class));
  }

  @Test
  void archivedReplayConflictsAndNewLateEventsAreDistinguishedWithoutRecreatingMonth() {
    LocalDate month = currentMonth.minusMonths(13);
    provision(month);
    var original = event(month, "original");
    store.append(original);
    archive(month);
    assertEquals(AuditEventStore.Result.DUPLICATE, store.append(original));
    var conflict =
        new SecurityEvent(
            original.eventId(),
            original.eventType(),
            1,
            original.occurredAt(),
            original.producer(),
            original.correlationId(),
            "different",
            original.context(),
            original.data());
    assertThrows(DuplicateKeyException.class, () -> store.append(conflict));
    var late = event(month, "late");
    assertThrows(ArchivedAuditMonthException.class, () -> store.append(late));
    assertEquals(0, receipts(late));
    var hot = event(currentMonth, "hot");
    assertThrows(ArchivedAuditMonthException.class, () -> store.appendBatch(List.of(hot, late)));
    assertEquals(0, receipts(hot));
    assertEquals(1, receipts(original));
    assertFalse(attached(month));
  }

  @Test
  void rollbackOfArchiveRestoresRoutingAndDoesNotLeaveCatalogOrGuardsBehind() {
    LocalDate month = currentMonth.minusMonths(14);
    provision(month);
    var original = event(month, "rollback");
    store.append(original);
    new TransactionTemplate(manager)
        .executeWithoutResult(
            status -> {
              assertEquals(1, archive(month));
              assertFalse(attached(month));
              status.setRollbackOnly();
            });
    assertTrue(attached(month));
    assertEquals(0, catalog(month));
    assertNull(
        owner.queryForObject("SELECT to_regclass(?)::text", String.class, archivedTable(month)));
    assertEquals(AuditEventStore.Result.APPENDED, store.append(event(month, "after-rollback")));
    assertEquals(2, archive(month));
  }

  @Test
  void boundsProtectCurrentAndPreviousMonthsAndRejectMissingOrNonMonthlyCandidates() {
    for (LocalDate month :
        List.of(
            currentMonth, currentMonth.minusMonths(1), currentMonth.minusMonths(12).plusDays(1))) {
      assertThrows(DataAccessException.class, () -> archive(month));
    }
    for (Integer hotMonths : new Integer[] {null, 1, 121}) {
      assertThrows(
          DataAccessException.class,
          () ->
              owner.queryForObject(
                  "SELECT iam_audit.archive_event_month(?,?)",
                  Long.class,
                  Date.valueOf(currentMonth.minusMonths(15)),
                  hotMonths));
    }
    assertThrows(
        DataAccessException.class,
        () -> owner.queryForObject("SELECT iam_audit.archive_event_month(NULL,6)", Long.class));
    assertThrows(DataAccessException.class, () -> archive(currentMonth.minusMonths(50)));
    LocalDate absent = currentMonth.minusMonths(16);
    owner.execute(
        "CREATE TABLE iam_audit.event_log_"
            + absent.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM"))
            + "(id integer)");
    assertThrows(DataAccessException.class, () -> archive(absent));
    assertEquals(0, catalog(absent));
  }

  @Test
  void lockTimeoutLeavesEverythingAttachedAndRetryCanComplete() throws Exception {
    LocalDate month = currentMonth.minusMonths(17);
    provision(month);
    store.append(event(month, "locked"));
    try (var blocker = DATABASE.createConnection("");
        var operation = DATABASE.createConnection("")) {
      blocker.setAutoCommit(false);
      blocker.createStatement().execute("LOCK TABLE iam_audit.event_log IN ACCESS SHARE MODE");
      operation.createStatement().execute("SET lock_timeout='200ms'");
      try (var statement =
          operation.prepareStatement("SELECT iam_audit.archive_event_month(?,6)")) {
        statement.setDate(1, Date.valueOf(month));
        var denied = assertThrows(SQLException.class, statement::execute);
        assertEquals("55P03", denied.getSQLState());
      }
      assertTrue(attached(month));
      assertEquals(0, catalog(month));
      blocker.rollback();
    }
    assertEquals(1, archive(month));
  }

  @Test
  void runtimeIdentitiesCannotArchiveReadOrMutateArchiveAndCannotReadReceipts() throws Exception {
    LocalDate month = currentMonth.minusMonths(18);
    provision(month);
    store.append(event(month, "permissions"));
    archive(month);
    for (var login :
        Map.of(
                "securegate_audit_ingest_login",
                INGEST_PASSWORD,
                "securegate_audit_maintenance_login",
                MAINTENANCE_PASSWORD)
            .entrySet()) {
      try (var connection =
          DriverManager.getConnection(DATABASE.getJdbcUrl(), login.getKey(), login.getValue())) {
        for (String sql :
            List.of(
                "SELECT iam_audit.archive_event_month(DATE '" + month + "',6)",
                "SELECT * FROM " + archivedTable(month),
                "SELECT * FROM iam_audit.archived_months",
                "SELECT * FROM iam_audit.event_receipts",
                "TRUNCATE " + archivedTable(month))) {
          try (var statement = connection.createStatement()) {
            var denied = assertThrows(SQLException.class, () -> statement.execute(sql));
            assertEquals("42501", denied.getSQLState());
          }
        }
      }
    }
  }

  @Test
  void operatorPsqlPlanIsReadOnlyAndApplyIsExplicitAndRepeatable() throws Exception {
    LocalDate month = currentMonth.minusMonths(19);
    provision(month);
    store.append(event(month, "operator"));
    String script = Files.readString(Path.of("../infra/scripts/Archive-AuditMonth.ps1"));
    var blocks = java.util.regex.Pattern.compile("(?s)@'\\r?\\n(.*?)\\r?\\n'@").matcher(script);
    assertTrue(blocks.find());
    String prefix = blocks.group(1);
    assertTrue(blocks.find());
    String plan = blocks.group(1);
    var apply =
        java.util.regex.Pattern.compile("(?s)\\$operation = if \\(\\$Apply\\) \\{\\s*\"([^\"]+)\"")
            .matcher(script);
    assertTrue(apply.find());
    runOperator(prefix + "\n" + plan, month);
    assertTrue(attached(month));
    assertEquals(0, catalog(month));
    runOperator(prefix + "\n" + apply.group(1), month);
    runOperator(prefix + "\n" + apply.group(1), month);
    assertFalse(attached(month));
    assertEquals(1, catalog(month));
  }

  @Test
  void replayWaitingForArchiveCommitRemainsDeduplicated() throws Exception {
    LocalDate month = currentMonth.minusMonths(20);
    provision(month);
    var original = event(month, "concurrent");
    store.append(original);
    try (var connection = DATABASE.createConnection("")) {
      connection.setAutoCommit(false);
      try (var statement =
          connection.prepareStatement("SELECT iam_audit.archive_event_month(?,6)")) {
        statement.setDate(1, Date.valueOf(month));
        statement.execute();
      }
      var started = new java.util.concurrent.CountDownLatch(1);
      var replay =
          java.util.concurrent.CompletableFuture.supplyAsync(
              () -> {
                started.countDown();
                return store.append(original);
              });
      assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS));
      connection.commit();
      assertEquals(
          AuditEventStore.Result.DUPLICATE, replay.get(5, java.util.concurrent.TimeUnit.SECONDS));
    }
    assertFalse(attached(month));
    assertEquals(1, receipts(original));
    assertEquals(
        1, owner.queryForObject("SELECT count(*) FROM " + archivedTable(month), Integer.class));
  }

  private static void runOperator(String sql, LocalDate month) throws Exception {
    DATABASE.copyFileToContainer(
        org.testcontainers.images.builder.Transferable.of(
            sql.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
        "/tmp/audit-archive-operator.sql");
    var result =
        DATABASE.execInContainer(
            "env",
            "AUDIT_ARCHIVE_MONTH=" + month,
            "AUDIT_HOT_MONTHS=6",
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
            "/tmp/audit-archive-operator.sql");
    assertEquals(0, result.getExitCode(), "Operator psql operation failed.");
    assertFalse(result.getStdout().contains(INGEST_PASSWORD));
    assertFalse(result.getStderr().contains(INGEST_PASSWORD));
  }

  private static void provision(LocalDate month) {
    owner.queryForObject(
        "SELECT iam_audit.ensure_event_partitions(?,1)", Integer.class, Date.valueOf(month));
  }

  private static long archive(LocalDate month) {
    return owner.queryForObject(
        "SELECT iam_audit.archive_event_month(?,6)", Long.class, Date.valueOf(month));
  }

  private static boolean attached(LocalDate month) {
    return owner.queryForObject(
        "SELECT EXISTS(SELECT 1 FROM pg_inherits WHERE inhparent='iam_audit.event_log'::regclass AND inhrelid=to_regclass(?))",
        Boolean.class,
        "iam_audit.event_log_"
            + month.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM")));
  }

  private static int catalog(LocalDate month) {
    return owner.queryForObject(
        "SELECT count(*) FROM iam_audit.archived_months WHERE month=?",
        Integer.class,
        Date.valueOf(month));
  }

  private static int receipts(SecurityEvent event) {
    return owner.queryForObject(
        "SELECT count(*) FROM iam_audit.event_receipts WHERE id=?", Integer.class, event.eventId());
  }

  private static String archivedTable(LocalDate month) {
    return "iam_audit_archive.event_log_"
        + month.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM"));
  }

  private static SecurityEvent event(LocalDate month, String actor) {
    return new SecurityEvent(
        UUID.randomUUID(),
        "test.archive",
        1,
        month.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant(),
        "iam-core",
        "synthetic",
        actor,
        Map.of("resource", "synthetic"),
        Map.of());
  }

  private static String secret() {
    return UUID.randomUUID().toString().replace("-", "")
        + UUID.randomUUID().toString().replace("-", "");
  }
}

package com.securegate.iam;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.securegate.events.SecurityEvent;
import com.securegate.iam.audit.application.port.AuditEventStore.Result;
import com.securegate.iam.audit.infrastructure.AuditPartitionMaintenance;
import com.securegate.iam.audit.infrastructure.JdbcAuditEventStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Date;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class PartitionedAuditIT {
  @Container
  static final PostgreSQLContainer<?> DATABASE =
      new PostgreSQLContainer<>("postgres:16.15-alpine")
          .withDatabaseName("partitioned_audit_test")
          .withUsername("partitioned_audit_test")
          .withPassword(UUID.randomUUID().toString());

  private static final ObjectMapper JSON =
      new ObjectMapper()
          .findAndRegisterModules()
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  private static final UUID LEGACY = UUID.randomUUID();
  private static JdbcTemplate jdbc;
  private static DataSourceTransactionManager manager;
  private static JdbcAuditEventStore store;
  private static Flyway flyway;

  @BeforeAll
  static void prepare() throws Exception {
    var source =
        new DriverManagerDataSource(
            DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
    jdbc = new JdbcTemplate(source);
    manager = new DataSourceTransactionManager(source);
    Flyway.configure().dataSource(source).target("12").load().migrate();
    var legacy = event(LEGACY, "2024-03-14T12:00:00Z", "legacy");
    jdbc.update(
        "INSERT INTO iam_audit.security_events(id,occurred_at,actor,event_type,resource,correlation_id,details) VALUES (?,?::timestamptz,'synthetic','audit.test','test:resource','legacy','{}'::jsonb)",
        LEGACY,
        legacy.occurredAt().toString());
    jdbc.update(
        "INSERT INTO iam_audit.outbox_events(event_id,event_type,schema_version,occurred_at,payload) VALUES (?,'audit.test',1,?::timestamptz,?::jsonb)",
        LEGACY,
        legacy.occurredAt().toString(),
        JSON.writeValueAsString(legacy));
    flyway = Flyway.configure().dataSource(source).load();
    flyway.migrate();
    store = new JdbcAuditEventStore(jdbc, JSON, manager);
  }

  @Test
  void upgradesV12WithoutMovingOrLosingTheLegacyAuditAndOutbox() {
    assertEquals("14", flyway.info().current().getVersion().getVersion());
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM iam_audit.security_events a JOIN iam_audit.outbox_events o ON o.event_id=a.id WHERE a.id=? AND o.published_at IS NULL",
            Integer.class,
            LEGACY));
    assertEquals(0, receipts(LEGACY));
    assertEquals(0, flyway.migrate().migrationsExecuted);
    flyway.validate();
    assertTrue(
        jdbc.queryForObject(
            "SELECT count(*) >= 5 FROM pg_inherits WHERE inhparent='iam_audit.event_log'::regclass",
            Boolean.class));
  }

  @Test
  void routesAcrossTheUtcMonthBoundaryAndClonesTheCompositeKey() throws Exception {
    try (var connection = DATABASE.createConnection("");
        var statement = connection.createStatement()) {
      statement.execute("SET TIME ZONE 'America/Sao_Paulo'");
      statement.execute("SET DateStyle = 'ISO, DMY'");
      statement.execute("SELECT iam_audit.ensure_event_partitions(DATE '2028-01-01',2)");
    }
    var january = event(UUID.randomUUID(), "2028-01-31T23:59:59.999999Z", "january");
    var february = event(UUID.randomUUID(), "2028-02-01T00:00:00Z", "february");
    assertEquals(Result.APPENDED, store.append(january));
    assertEquals(Result.APPENDED, store.append(february));
    assertEquals("iam_audit.event_log_202801", partition(january.eventId()));
    assertEquals("iam_audit.event_log_202802", partition(february.eventId()));
    assertEquals(
        "PRIMARY KEY (id, occurred_at)",
        jdbc.queryForObject(
            "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid='iam_audit.event_log'::regclass AND contype='p'",
            String.class));
  }

  @Test
  void exactReplayIsIdempotentAndConflictingIdentityCannotCrossMonths() {
    ensure("2028-03-01", 2);
    UUID id = UUID.randomUUID();
    var event = event(id, "2028-03-15T10:00:00Z", "original");
    assertEquals(Result.APPENDED, store.append(event));
    assertEquals(Result.DUPLICATE, store.append(event));
    assertThrows(
        DataAccessException.class,
        () -> store.append(event(id, "2028-03-15T10:00:00Z", "changed")));
    assertThrows(
        DataAccessException.class,
        () -> store.append(event(id, "2028-04-15T10:00:00Z", "original")));
    assertEquals(1, rows(id));
    assertEquals(1, receipts(id));
  }

  @Test
  void concurrentExactReplaysCommitOneReceiptAndOnePartitionRow() throws Exception {
    ensure("2028-05-01", 1);
    for (int wave = 0; wave < 5; wave++) {
      var event = event(UUID.randomUUID(), "2028-05-05T10:00:00Z", "concurrent");
      var start = new CountDownLatch(1);
      try (var executor = Executors.newFixedThreadPool(8)) {
        var jobs =
            java.util.stream.IntStream.range(0, 8)
                .mapToObj(
                    i ->
                        executor.submit(
                            () -> {
                              start.await();
                              return store.append(event);
                            }))
                .toList();
        start.countDown();
        int appended = 0;
        int duplicated = 0;
        for (var job : jobs) {
          if (job.get(10, TimeUnit.SECONDS) == Result.APPENDED) appended++;
          else duplicated++;
        }
        assertEquals(1, appended);
        assertEquals(7, duplicated);
      }
      assertEquals(1, rows(event.eventId()));
      assertEquals(1, receipts(event.eventId()));
    }
  }

  @Test
  void concurrentConflictingPayloadsCannotBothCommit() throws Exception {
    ensure("2028-06-01", 1);
    UUID id = UUID.randomUUID();
    var start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var jobs =
          List.of("first", "second").stream()
              .map(
                  value ->
                      executor.submit(
                          () -> {
                            start.await();
                            try {
                              return store.append(event(id, "2028-06-01T10:00:00Z", value))
                                  == Result.APPENDED;
                            } catch (DataAccessException conflict) {
                              return false;
                            }
                          }))
              .toList();
      start.countDown();
      int committed = 0;
      for (var job : jobs) {
        if (job.get(10, TimeUnit.SECONDS)) committed++;
      }
      assertEquals(1, committed);
    }
    assertEquals(1, rows(id));
    assertEquals(1, receipts(id));
  }

  @Test
  void missingPartitionRollsBackReceiptAndProvisioningAllowsRetry() {
    var event = event(UUID.randomUUID(), "2050-06-05T10:00:00Z", "missing");
    assertThrows(DataAccessException.class, () -> store.append(event));
    assertEquals(0, rows(event.eventId()));
    assertEquals(0, receipts(event.eventId()));
    ensure("2050-06-01", 1);
    assertEquals(Result.APPENDED, store.append(event));
    assertEquals(1, receipts(event.eventId()));
  }

  @Test
  void outerTransactionRollbackCannotLeaveAReceiptOrAnAuditRow() {
    ensure("2028-07-01", 1);
    var event = event(UUID.randomUUID(), "2028-07-01T10:00:00Z", "rollback");
    assertThrows(
        IllegalStateException.class,
        () ->
            new TransactionTemplate(manager)
                .executeWithoutResult(
                    status -> {
                      assertEquals(Result.APPENDED, store.append(event));
                      throw new IllegalStateException("synthetic transaction rollback");
                    }));
    assertEquals(0, rows(event.eventId()));
    assertEquals(0, receipts(event.eventId()));
    assertEquals(Result.APPENDED, store.append(event));
  }

  @Test
  void tableAndDirectPartitionMutationsAndTruncationAreDenied() {
    ensure("2028-08-01", 1);
    var event = event(UUID.randomUUID(), "2028-08-05T10:00:00Z", "immutable");
    store.append(event);
    for (String table :
        List.of("iam_audit.event_log", "iam_audit.event_log_202808", "iam_audit.event_receipts")) {
      assertThrows(
          DataAccessException.class,
          () ->
              jdbc.update(
                  "UPDATE " + table + " SET received_at=current_timestamp WHERE id=?",
                  event.eventId()));
      assertThrows(
          DataAccessException.class,
          () -> jdbc.update("DELETE FROM " + table + " WHERE id=?", event.eventId()));
      assertThrows(DataAccessException.class, () -> jdbc.execute("TRUNCATE " + table + " CASCADE"));
    }
    assertEquals(1, rows(event.eventId()));
    assertEquals(1, receipts(event.eventId()));
  }

  @Test
  void partitionsAreBoundedIdempotentAndSafeUnderConcurrentMaintenance() throws Exception {
    var start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first =
          executor.submit(
              () -> {
                start.await();
                return ensure("2031-12-01", 3);
              });
      var second =
          executor.submit(
              () -> {
                start.await();
                return ensure("2031-12-01", 3);
              });
      start.countDown();
      assertEquals(3, first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS));
    }
    assertEquals(0, ensure("2031-12-01", 3));
    assertThrows(DataAccessException.class, () -> ensure("2031-12-01", 0));
    assertThrows(DataAccessException.class, () -> ensure("2031-12-01", 25));
    jdbc.execute("CREATE TABLE iam_audit.event_log_204001(id int)");
    try {
      assertThrows(DataAccessException.class, () -> ensure("2040-01-01", 1));
    } finally {
      jdbc.execute("DROP TABLE iam_audit.event_log_204001");
    }
  }

  @Test
  void maintenanceCreatesAheadOfDatabaseUtcTimeWithoutQueryingOnMetricScrape() {
    var meters = new SimpleMeterRegistry();
    var month =
        jdbc.queryForObject(
                "SELECT (date_trunc('month', clock_timestamp() AT TIME ZONE 'UTC') - interval '1 month')::date",
                Date.class)
            .toLocalDate();
    int missing = 0;
    for (int i = 0; i < 8; i++) {
      String name =
          "iam_audit.event_log_"
              + month.plusMonths(i).format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM"));
      if (jdbc.queryForObject("SELECT to_regclass(?) IS NULL", Boolean.class, name)) {
        missing++;
      }
    }
    try (var worker = new AuditPartitionMaintenance(jdbc, meters, 6, 1000)) {
      worker.runOnce();
      assertEquals(1.0, meters.get("iam.audit.partitions.available").gauge().value());
      assertEquals((double) missing, meters.get("iam.audit.partitions.created").counter().count());
      worker.runOnce();
      assertEquals((double) missing, meters.get("iam.audit.partitions.created").counter().count());
      assertEquals(0, ensure(month.toString(), 8));
    }
  }

  @Test
  void invalidEnvelopeSizeCannotLeaveAnOrphanReceipt() {
    ensure("2028-09-01", 1);
    UUID id = UUID.randomUUID();
    var normal = event(id, "2028-09-10T10:00:00Z", "normal");
    var metadata = new java.util.HashMap<String, String>();
    for (int i = 0; i < 21; i++) {
      metadata.put("item" + i, "x".repeat(4000));
    }
    var oversized =
        new SecurityEvent(
            id,
            normal.eventType(),
            1,
            normal.occurredAt(),
            normal.producer(),
            normal.correlationId(),
            normal.actor(),
            normal.context(),
            metadata);
    assertThrows(DataAccessException.class, () -> store.append(oversized));
    assertEquals(0, rows(id));
    assertEquals(0, receipts(id));
    assertEquals(Result.APPENDED, store.append(normal));
  }

  private static SecurityEvent event(UUID id, String time, String value) {
    return new SecurityEvent(
        id,
        "audit.test",
        1,
        Instant.parse(time),
        "iam-core",
        "synthetic-test",
        "synthetic",
        Map.of("resource", "test:resource"),
        Map.of("value", value));
  }

  private static int ensure(String month, int count) {
    return jdbc.queryForObject(
        "SELECT iam_audit.ensure_event_partitions(?,?)", Integer.class, Date.valueOf(month), count);
  }

  private static int receipts(UUID id) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM iam_audit.event_receipts WHERE id=?", Integer.class, id);
  }

  private static int rows(UUID id) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM iam_audit.event_log WHERE id=?", Integer.class, id);
  }

  private static String partition(UUID id) {
    return jdbc.queryForObject(
        "SELECT tableoid::regclass::text FROM iam_audit.event_log WHERE id=?", String.class, id);
  }
}

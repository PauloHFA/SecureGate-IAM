package com.securegate.iam;

import static org.junit.jupiter.api.Assertions.*;

import com.securegate.events.SecurityEventType;
import com.securegate.iam.audit.application.port.AuditRecord;
import com.securegate.iam.audit.application.port.AuditTrail;
import com.securegate.iam.audit.application.port.EventTransport;
import com.securegate.iam.audit.infrastructure.JdbcOutboxDelivery;
import com.securegate.iam.audit.infrastructure.JdbcOutboxOperations;
import com.securegate.iam.audit.infrastructure.OutboxMaintenance;
import com.securegate.iam.shared.application.port.OperationContext;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OutboxOperationsIT {
  private static final String TOKEN = UUID.randomUUID().toString();

  @Container
  static final PostgreSQLContainer<?> DATABASE =
      new PostgreSQLContainer<>("postgres:16.15-alpine").withPassword(UUID.randomUUID().toString());

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
    registry.add("spring.datasource.username", DATABASE::getUsername);
    registry.add("spring.datasource.password", DATABASE::getPassword);
    registry.add("iam.bootstrap.enabled", () -> true);
    registry.add("iam.bootstrap.token", () -> TOKEN);
  }

  @Autowired JdbcTemplate jdbc;
  @Autowired AuditTrail audit;
  @Autowired JdbcOutboxOperations operations;
  @Autowired PlatformTransactionManager manager;
  @Autowired OutboxMaintenance maintenance;
  @Autowired MeterRegistry meters;
  @LocalServerPort int port;

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM iam_audit.outbox_events");
  }

  private UUID event() {
    String correlation = UUID.randomUUID().toString();
    audit.record(
        new AuditRecord(
            new SecurityEventType("test.operations"),
            "synthetic",
            new OperationContext("operations-test", correlation),
            Map.of("version", "1")));
    return jdbc.queryForObject(
        "SELECT event_id FROM iam_audit.outbox_events WHERE payload->>'correlationId'=?",
        UUID.class,
        correlation);
  }

  private void oldPublished(UUID id) {
    jdbc.update(
        "UPDATE iam_audit.outbox_events SET published_at=clock_timestamp()-interval '8 days' WHERE event_id=?",
        id);
  }

  private boolean exists(UUID id) {
    return jdbc.queryForObject(
            "SELECT count(*) FROM iam_audit.outbox_events WHERE event_id=?", Integer.class, id)
        == 1;
  }

  @Test
  void retentionIsBoundedAndKeepsAuditFreshPublishedPendingAndQuarantinedEvents() {
    UUID old = event();
    oldPublished(old);
    UUID second = event();
    oldPublished(second);
    UUID fresh = event();
    jdbc.update(
        "UPDATE iam_audit.outbox_events SET published_at=clock_timestamp() WHERE event_id=?",
        fresh);
    UUID pending = event();
    UUID quarantined = event();
    jdbc.queryForObject(
        "SELECT iam_audit.set_outbox_quarantine(?,true)", Boolean.class, quarantined);
    assertEquals(1, operations.purgePublished(7, 1));
    assertEquals(1, operations.purgePublished(7, 100));
    assertEquals(0, operations.purgePublished(7, 100));
    assertFalse(exists(old));
    assertFalse(exists(second));
    assertTrue(exists(fresh));
    assertTrue(exists(pending));
    assertTrue(exists(quarantined));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM iam_audit.security_events WHERE id=?", Integer.class, old));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM iam_audit.outbox_operations WHERE event_id=?",
            Integer.class,
            quarantined));
    assertThrows(IllegalArgumentException.class, () -> operations.purgePublished(0, 10));
    assertThrows(IllegalArgumentException.class, () -> operations.purgePublished(7, 1001));
  }

  @Test
  void lockedRetentionCandidateIsSkippedAndRollbackRestoresDeletion() {
    UUID locked = event();
    oldPublished(locked);
    UUID free = event();
    oldPublished(free);
    new TransactionTemplate(manager)
        .executeWithoutResult(
            status -> {
              jdbc.queryForObject(
                  "SELECT event_id FROM iam_audit.outbox_events WHERE event_id=? FOR UPDATE",
                  UUID.class,
                  locked);
              assertEquals(
                  1,
                  assertDoesNotThrow(
                      () ->
                          CompletableFuture.supplyAsync(() -> operations.purgePublished(7, 100))
                              .get(5, TimeUnit.SECONDS)));
              assertTrue(exists(locked));
              assertFalse(exists(free));
            });
    jdbc.execute(
        """
        CREATE FUNCTION iam_audit.test_retention_failure() RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN RAISE EXCEPTION 'synthetic retention failure'; END; $$
        """);
    jdbc.execute(
        "CREATE TRIGGER test_retention_failure AFTER DELETE ON iam_audit.outbox_events FOR EACH STATEMENT EXECUTE FUNCTION iam_audit.test_retention_failure()");
    try {
      assertThrows(
          org.springframework.dao.DataAccessException.class, () -> operations.purgePublished(7, 1));
      assertTrue(exists(locked));
    } finally {
      jdbc.execute("DROP TRIGGER test_retention_failure ON iam_audit.outbox_events");
      jdbc.execute("DROP FUNCTION iam_audit.test_retention_failure()");
    }
    assertEquals(1, operations.purgePublished(7, 1));
  }

  @Test
  void permanentFailureQuarantinesWithoutDiscardAndOperatorReleasePreservesPayload() {
    UUID id = event();
    String payload =
        jdbc.queryForObject(
            "SELECT payload::text FROM iam_audit.outbox_events WHERE event_id=?", String.class, id);
    var delivery = new JdbcOutboxDelivery(jdbc, manager);
    var claim = delivery.claim(30).orElseThrow();
    assertTrue(delivery.retry(claim, EventTransport.Outcome.INVALID_EVENT, 2));
    assertTrue(delivery.claim(30).isEmpty());
    assertEquals(1L, ((Number) operations.snapshot().get("quarantined")).longValue());
    assertEquals(
        "INVALID_EVENT",
        jdbc.queryForObject(
            "SELECT reason FROM iam_audit.outbox_operations WHERE event_id=?", String.class, id));
    assertTrue(
        jdbc.queryForObject("SELECT iam_audit.set_outbox_quarantine(?,false)", Boolean.class, id));
    assertFalse(
        jdbc.queryForObject("SELECT iam_audit.set_outbox_quarantine(?,false)", Boolean.class, id));
    var recovered = delivery.claim(30).orElseThrow();
    assertEquals(id, recovered.eventId());
    assertEquals(payload, recovered.payload());
    assertEquals(
        2,
        jdbc.queryForObject(
            "SELECT count(*) FROM iam_audit.outbox_operations WHERE event_id=?",
            Integer.class,
            id));
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> jdbc.update("DELETE FROM iam_audit.outbox_operations WHERE event_id=?", id));
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            jdbc.update(
                "UPDATE iam_audit.outbox_events SET payload=payload||'{\"extra\":true}' WHERE event_id=?",
                id));
  }

  @Test
  void operatorQuarantineFencesAnActivePublisherAndCannotReopenPublishedEvents() {
    UUID id = event();
    var delivery = new JdbcOutboxDelivery(jdbc, manager);
    var old = delivery.claim(30).orElseThrow();
    assertTrue(
        jdbc.queryForObject("SELECT iam_audit.set_outbox_quarantine(?,true)", Boolean.class, id));
    assertFalse(delivery.published(old));
    assertFalse(delivery.retry(old, EventTransport.Outcome.NACK, 2));
    assertTrue(
        jdbc.queryForObject("SELECT iam_audit.set_outbox_quarantine(?,false)", Boolean.class, id));
    assertTrue(delivery.published(delivery.claim(30).orElseThrow()));
    assertFalse(
        jdbc.queryForObject("SELECT iam_audit.set_outbox_quarantine(?,true)", Boolean.class, id));
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            jdbc.queryForObject(
                "SELECT iam_audit.set_outbox_quarantine(?,NULL)", Boolean.class, id));
  }

  @Test
  void cachedMetricsReflectBacklogAndRequireAdministrativeAuthentication() throws Exception {
    UUID leased = event();
    new JdbcOutboxDelivery(jdbc, manager).claim(30).orElseThrow();
    UUID waiting = event();
    jdbc.update(
        "UPDATE iam_audit.outbox_events SET next_attempt_at=clock_timestamp()+interval '1 minute',last_error_code='NACK' WHERE event_id=?",
        waiting);
    event();
    UUID quarantined = event();
    jdbc.queryForObject(
        "SELECT iam_audit.set_outbox_quarantine(?,true)", Boolean.class, quarantined);
    maintenance.runOnce();
    assertEquals(4.0, meters.get("iam.outbox.pending").gauge().value());
    assertEquals(1.0, meters.get("iam.outbox.due").gauge().value());
    assertEquals(1.0, meters.get("iam.outbox.leased").gauge().value());
    assertEquals(1.0, meters.get("iam.outbox.failed").gauge().value());
    assertEquals(1.0, meters.get("iam.outbox.quarantined").gauge().value());
    assertTrue(meters.get("iam.outbox.oldest_seconds").gauge().value() >= 0);
    var client = HttpClient.newHttpClient();
    URI uri = URI.create("http://127.0.0.1:" + port + "/actuator/metrics/iam.outbox.pending");
    assertEquals(
        401,
        client
            .send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString())
            .statusCode());
    assertEquals(
        401,
        client
            .send(
                HttpRequest.newBuilder(uri).header("Authorization", "Bearer invalid").GET().build(),
                HttpResponse.BodyHandlers.ofString())
            .statusCode());
    var response =
        client.send(
            HttpRequest.newBuilder(uri).header("Authorization", "Bearer " + TOKEN).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode());
    assertTrue(response.body().contains("iam.outbox.pending"));
    assertFalse(response.body().contains(leased.toString()));
    assertFalse(response.body().contains(TOKEN));
  }

  @Test
  void defaultMaintenanceDoesNotDeleteEligiblePublishedEvents() {
    UUID id = event();
    oldPublished(id);
    maintenance.runOnce();
    assertTrue(exists(id));
    try (var enabled =
        new OutboxMaintenance(
            operations,
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
            true,
            7,
            1,
            1000)) {
      enabled.runOnce();
      assertFalse(exists(id));
    }
  }
}

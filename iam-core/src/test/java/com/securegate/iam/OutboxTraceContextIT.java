package com.securegate.iam;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securegate.events.SecurityEvent;
import com.securegate.events.SecurityEventType;
import com.securegate.iam.audit.application.port.*;
import com.securegate.iam.audit.infrastructure.*;
import com.securegate.iam.shared.application.port.OperationContext;
import io.micrometer.tracing.Tracer;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@AutoConfigureObservability
@ActiveProfiles("http-tracing")
@SpringBootTest(properties = "management.tracing.sampling.probability=1")
class OutboxTraceContextIT {
  @Container
  static final PostgreSQLContainer<?> DB =
      new PostgreSQLContainer<>("postgres:16.15-alpine").withPassword(UUID.randomUUID().toString());

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", DB::getJdbcUrl);
    r.add("spring.datasource.username", DB::getUsername);
    r.add("spring.datasource.password", DB::getPassword);
  }

  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager manager;
  @Autowired AuditTrail audit;
  @Autowired ObjectMapper json;
  @Autowired Tracer tracer;

  @BeforeEach
  void clearPending() {
    jdbc.update("DELETE FROM iam_audit.outbox_events");
  }

  UUID record(String correlation) {
    audit.record(
        new AuditRecord(
            new SecurityEventType("test.outbox"),
            "synthetic",
            new OperationContext("test", correlation),
            Map.of("version", "1")));
    return jdbc.queryForObject(
        "SELECT event_id FROM iam_audit.outbox_events WHERE payload->>'correlationId'=?",
        UUID.class,
        correlation);
  }

  String trace(UUID id) {
    return jdbc.queryForObject(
        "SELECT trace_parent FROM iam_audit.outbox_events WHERE event_id=?", String.class, id);
  }

  @Test
  void upgradesV22WithoutInventingContextOrChangingExistingPayload() throws Exception {
    UUID event = record(UUID.randomUUID().toString());
    String payload =
        jdbc.queryForObject(
            "SELECT payload::text FROM iam_audit.outbox_events WHERE event_id=?",
            String.class,
            event);
    SecurityEvent envelope = json.readValue(payload, SecurityEvent.class);
    String name = "legacy_trace_" + UUID.randomUUID().toString().replace("-", "");
    jdbc.execute("CREATE DATABASE " + name);
    String url = DB.getJdbcUrl().replace("/" + DB.getDatabaseName(), "/" + name);
    var flyway =
        org.flywaydb.core.Flyway.configure().dataSource(url, DB.getUsername(), DB.getPassword());
    flyway.target("22").load().migrate();
    var source =
        new org.springframework.jdbc.datasource.DriverManagerDataSource(
            url, DB.getUsername(), DB.getPassword());
    var legacy = new JdbcTemplate(source);
    legacy.update(
        "INSERT INTO iam_audit.security_events(id,occurred_at,actor,event_type,resource,correlation_id,details) VALUES (?,?::timestamptz,?,?,?,?,?::jsonb)",
        event,
        envelope.occurredAt().toString(),
        envelope.actor(),
        envelope.eventType(),
        envelope.context().get("resource"),
        envelope.correlationId(),
        json.writeValueAsString(envelope.data()));
    legacy.update(
        "INSERT INTO iam_audit.outbox_events(event_id,event_type,schema_version,occurred_at,payload) VALUES (?,?,?,?::timestamptz,?::jsonb)",
        event,
        envelope.eventType(),
        envelope.schemaVersion(),
        envelope.occurredAt().toString(),
        payload);
    org.flywaydb.core.Flyway.configure()
        .dataSource(url, DB.getUsername(), DB.getPassword())
        .load()
        .migrate();
    assertThat(
            legacy.queryForObject(
                "SELECT trace_parent FROM iam_audit.outbox_events WHERE event_id=?",
                String.class,
                event))
        .isNull();
    assertThat(
            legacy.queryForObject(
                "SELECT payload::text FROM iam_audit.outbox_events WHERE event_id=?",
                String.class,
                event))
        .isEqualTo(payload);
    var recovered =
        new JdbcOutboxDelivery(
                legacy,
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(source))
            .claim(30)
            .orElseThrow();
    assertThat(recovered.eventId()).isEqualTo(event);
    assertThat(recovered.traceParent()).isNull();
  }

  @Test
  void contextSurvivesScopeExitRetryAndReconstructedPublisherStore() throws Exception {
    UUID event;
    var parent = tracer.nextSpan().name("synthetic-parent").start();
    String expected = "00-" + parent.context().traceId() + "-" + parent.context().spanId() + "-01";
    try (var scope = tracer.withSpan(parent)) {
      event = record(UUID.randomUUID().toString());
    } finally {
      parent.end();
    }
    assertThat(tracer.currentSpan()).isNull();
    assertThat(trace(event)).isEqualTo(expected);
    var store = new JdbcOutboxDelivery(jdbc, manager);
    var first = store.claim(30).orElseThrow();
    assertThat(first.traceParent()).isEqualTo(expected);
    SecurityEvent payload = json.readValue(first.payload(), SecurityEvent.class);
    assertThat(payload.eventId()).isEqualTo(event);
    assertThat(first.payload())
        .doesNotContain("traceparent", "trace_parent", "tracestate", "baggage", expected);
    assertThat(first.toString()).doesNotContain(expected, parent.context().traceId());
    assertThat(store.retry(first, EventTransport.Outcome.BROKER_UNAVAILABLE, 1)).isTrue();
    jdbc.update(
        "UPDATE iam_audit.outbox_events SET next_attempt_at=statement_timestamp() WHERE event_id=?",
        event);
    var second = new JdbcOutboxDelivery(jdbc, manager).claim(30).orElseThrow();
    assertThat(second.claimId()).isNotEqualTo(first.claimId());
    assertThat(second.traceParent()).isEqualTo(expected);
    assertThat(second.payload()).isEqualTo(first.payload());
    assertThat(second.attempts()).isEqualTo(2);
    assertThat(store.published(first)).isFalse();
    assertThat(store.published(second)).isTrue();
    assertThat(trace(event)).isEqualTo(expected);
  }

  @Test
  void nullContextRemainsCompatibleAndInvalidSupplierIsDiscarded() throws Exception {
    UUID event = record(UUID.randomUUID().toString());
    assertThat(trace(event)).isNull();
    var envelope =
        json.readValue(
            jdbc.queryForObject(
                "SELECT payload::text FROM iam_audit.outbox_events WHERE event_id=?",
                String.class,
                event),
            SecurityEvent.class);
    jdbc.update("DELETE FROM iam_audit.outbox_events WHERE event_id=?", event);
    new TransactionTemplate(manager)
        .executeWithoutResult(
            status ->
                new JdbcOutboxEvents(jdbc, json, () -> "token=synthetic-secret").append(envelope));
    assertThat(trace(event)).isNull();
    assertThat(new JdbcOutboxDelivery(jdbc, manager).claim(30).orElseThrow().traceParent())
        .isNull();
  }

  @Test
  void rollbackRemovesAuditAndOutboxContextTogether() {
    String correlation = UUID.randomUUID().toString();
    var span = tracer.nextSpan().name("rollback").start();
    try (var scope = tracer.withSpan(span)) {
      new TransactionTemplate(manager)
          .executeWithoutResult(
              status -> {
                record(correlation);
                assertThat(
                        jdbc.queryForObject(
                            "SELECT count(*) FROM iam_audit.outbox_events WHERE trace_parent IS NOT NULL",
                            Integer.class))
                    .isEqualTo(1);
                status.setRollbackOnly();
              });
    } finally {
      span.end();
    }
    assertThat(jdbc.queryForObject("SELECT count(*) FROM iam_audit.outbox_events", Integer.class))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM iam_audit.security_events WHERE correlation_id=?",
                Integer.class,
                correlation))
        .isZero();
  }

  @Test
  void sqlRejectsMalformedParentsAndMutationEvenForOwner() {
    UUID event = record(UUID.randomUUID().toString());
    String payload =
        jdbc.queryForObject(
            "SELECT payload::text FROM iam_audit.outbox_events WHERE event_id=?",
            String.class,
            event);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE iam_audit.outbox_events SET trace_parent=? WHERE event_id=?",
                    "00-" + "1".repeat(32) + "-" + "2".repeat(16) + "-01",
                    event))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    jdbc.update("DELETE FROM iam_audit.outbox_events WHERE event_id=?", event);
    for (String invalid :
        java.util.List.of(
            "synthetic-secret",
            "00-" + "0".repeat(32) + "-" + "2".repeat(16) + "-01",
            "00-" + "1".repeat(32) + "-" + "0".repeat(16) + "-01",
            "00-" + "1".repeat(32) + "-" + "2".repeat(16) + "-ff")) {
      assertThatThrownBy(
              () ->
                  jdbc.update(
                      "INSERT INTO iam_audit.outbox_events(event_id,event_type,schema_version,occurred_at,payload,trace_parent) SELECT id,event_type,1,occurred_at,?::jsonb,? FROM iam_audit.security_events WHERE id=?",
                      payload,
                      invalid,
                      event))
          .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
  }
}

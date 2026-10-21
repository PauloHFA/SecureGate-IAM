package com.securegate.iam.audit.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.securegate.events.SecurityEvent;
import com.securegate.iam.audit.application.port.OutboxTraceParent;
import java.sql.Timestamp;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Append only inside the same database transaction as the business change and its audit. */
public class JdbcOutboxEvents {
  private final JdbcTemplate jdbc;
  private final ObjectWriter json;
  private final Supplier<String> traceParent;

  public JdbcOutboxEvents(JdbcTemplate jdbc, ObjectMapper json) {
    this(jdbc, json, () -> null);
  }

  public JdbcOutboxEvents(JdbcTemplate jdbc, ObjectMapper json, Supplier<String> traceParent) {
    this.traceParent = traceParent;
    this.jdbc = jdbc;
    this.json =
        json.writerFor(SecurityEvent.class).without(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  }

  public void append(SecurityEvent event) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("Outbox append requires a transaction.");
    }
    try {
      jdbc.update(
          "INSERT INTO iam_audit.outbox_events(event_id,event_type,schema_version,occurred_at,payload,trace_parent) VALUES (?,?,?,?,?::jsonb,?)",
          event.eventId(),
          event.eventType(),
          event.schemaVersion(),
          Timestamp.from(event.occurredAt()),
          json.writeValueAsString(event),
          OutboxTraceParent.safeOrNull(traceParent.get()));
    } catch (JsonProcessingException failure) {
      throw new IllegalStateException("Event serialization failed.");
    }
  }
}

package com.securegate.iam.audit.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securegate.events.SecurityEvent;
import com.securegate.iam.audit.application.port.AuditRecord;
import com.securegate.iam.audit.application.port.AuditTrail;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

public class JdbcAuditTrail implements AuditTrail {
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final JdbcOutboxEvents outbox;
  private final TransactionTemplate transactions;
  private final Clock clock;

  public JdbcAuditTrail(
      JdbcTemplate jdbc,
      ObjectMapper json,
      JdbcOutboxEvents outbox,
      TransactionTemplate transactions,
      Clock clock) {
    this.jdbc = jdbc;
    this.json = json;
    this.outbox = outbox;
    this.transactions = transactions;
    this.clock = clock;
  }

  @Override
  public void record(AuditRecord event) {
    transactions.executeWithoutResult(
        status -> {
          var envelope =
              new SecurityEvent(
                  UUID.randomUUID(),
                  event.type().value(),
                  SecurityEvent.CURRENT_VERSION,
                  clock.instant().truncatedTo(ChronoUnit.MICROS),
                  "iam-core",
                  event.context().correlationId(),
                  event.context().actor(),
                  Map.of("resource", event.resource()),
                  event.details());
          try {
            jdbc.update(
                "INSERT INTO iam_audit.security_events (id,occurred_at,actor,event_type,resource,correlation_id,details) VALUES (?,?,?,?,?,?,?::jsonb)",
                envelope.eventId(),
                Timestamp.from(envelope.occurredAt()),
                event.context().actor(),
                event.type().value(),
                event.resource(),
                event.context().correlationId(),
                json.writeValueAsString(event.details()));
            outbox.append(envelope);
          } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Audit serialization failed");
          }
        });
  }
}

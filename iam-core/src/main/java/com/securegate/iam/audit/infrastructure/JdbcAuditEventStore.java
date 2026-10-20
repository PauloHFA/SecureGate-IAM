package com.securegate.iam.audit.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.securegate.events.SecurityEvent;
import com.securegate.iam.audit.application.port.AuditEventStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Global receipt and monthly row commit in the same transaction, including concurrent replay. */
public final class JdbcAuditEventStore implements AuditEventStore {
  private final JdbcTemplate jdbc;
  private final ObjectWriter json;
  private final TransactionTemplate transactions;

  public JdbcAuditEventStore(
      JdbcTemplate jdbc, ObjectMapper json, PlatformTransactionManager manager) {
    this.jdbc = jdbc;
    this.json =
        json.writerFor(SecurityEvent.class).without(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    this.transactions = new TransactionTemplate(manager);
  }

  @Override
  public Result append(SecurityEvent event) {
    final String body;
    try {
      body = json.writeValueAsString(event);
    } catch (JsonProcessingException invalid) {
      throw new IllegalArgumentException("Invalid audit envelope.");
    }
    return transactions.execute(
        status -> {
          int count =
              jdbc.update(
                  """
          INSERT INTO iam_audit.event_log(id,occurred_at,event_type,schema_version,producer,correlation_id,actor,resource,payload)
          SELECT (body->>'eventId')::uuid,(body->>'occurredAt')::timestamptz,body->>'eventType',
            (body->>'schemaVersion')::smallint,body->>'producer',body->>'correlationId',body->>'actor',body->'context'->>'resource',body
          FROM (SELECT ?::jsonb AS body) envelope
          """,
                  body);
          return count == 1 ? Result.APPENDED : Result.DUPLICATE;
        });
  }
}

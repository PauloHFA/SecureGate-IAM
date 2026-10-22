package com.securegate.iam.audit.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.securegate.events.SecurityEvent;
import com.securegate.iam.audit.application.ArchivedAuditMonthException;
import com.securegate.iam.audit.application.port.AuditEventStore;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
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
    return appendBatch(List.of(event)).getFirst();
  }

  @Override
  public List<Result> appendBatch(List<SecurityEvent> events) {
    if (events == null || events.isEmpty() || events.size() > 100) {
      throw new IllegalArgumentException("Invalid audit batch bounds.");
    }
    var bodies = new ArrayList<String>(events.size());
    for (var event : events) {
      if (event == null) {
        throw new IllegalArgumentException("Invalid audit envelope.");
      }
      try {
        bodies.add(json.writeValueAsString(event));
      } catch (JsonProcessingException invalid) {
        throw new IllegalArgumentException("Invalid audit envelope.");
      }
    }
    try {
      return transactions.execute(
          status -> {
            int[] counts =
                jdbc.batchUpdate(
                    """
          INSERT INTO iam_audit.event_log(id,occurred_at,event_type,schema_version,producer,correlation_id,actor,resource,payload)
          SELECT (body->>'eventId')::uuid,(body->>'occurredAt')::timestamptz,body->>'eventType',
            (body->>'schemaVersion')::smallint,body->>'producer',body->>'correlationId',body->>'actor',body->'context'->>'resource',body
          FROM (SELECT ?::jsonb AS body) envelope
          WHERE NOT iam_audit.is_archived_event_replay(body)
          """,
                    new BatchPreparedStatementSetter() {
                      @Override
                      public void setValues(java.sql.PreparedStatement statement, int index)
                          throws java.sql.SQLException {
                        statement.setString(1, bodies.get(index));
                      }

                      @Override
                      public int getBatchSize() {
                        return bodies.size();
                      }
                    });
            var results = new ArrayList<Result>(counts.length);
            for (int count : counts) {
              // Unknown update counts cannot be treated as a committed, classified batch.
              if (count != 0 && count != 1) {
                throw new IllegalStateException("Unexpected audit batch result.");
              }
              results.add(count == 1 ? Result.APPENDED : Result.DUPLICATE);
            }
            return List.copyOf(results);
          });
    } catch (org.springframework.dao.DataAccessException failure) {
      for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
        if (cause instanceof java.sql.SQLException sql) {
          for (var next = sql; next != null; next = next.getNextException()) {
            if ("SG001".equals(next.getSQLState())) {
              throw new ArchivedAuditMonthException();
            }
          }
        }
      }
      throw failure;
    }
  }
}

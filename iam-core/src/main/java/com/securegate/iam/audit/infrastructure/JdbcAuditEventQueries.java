package com.securegate.iam.audit.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securegate.events.SecurityEvent;
import com.securegate.iam.audit.application.AuditEventView;
import com.securegate.iam.audit.application.AuditQuery;
import com.securegate.iam.audit.application.port.AuditEventQueries;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

public final class JdbcAuditEventQueries implements AuditEventQueries {
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final TransactionTemplate transactions;

  public JdbcAuditEventQueries(
      JdbcTemplate jdbc, ObjectMapper json, DataSourceTransactionManager manager) {
    this.jdbc = jdbc;
    this.json = json;
    manager.setEnforceReadOnly(true);
    transactions = new TransactionTemplate(manager);
    transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    transactions.setReadOnly(true);
    transactions.setTimeout(5);
  }

  @Override
  public List<AuditEventView> find(AuditQuery query) {
    var sql =
        new StringBuilder(
            "SELECT id,occurred_at,received_at,payload::text FROM iam_audit.event_log WHERE occurred_at>=? AND occurred_at<?");
    var parameters = new ArrayList<Object>();
    parameters.add(OffsetDateTime.ofInstant(query.from(), ZoneOffset.UTC));
    parameters.add(OffsetDateTime.ofInstant(query.to(), ZoneOffset.UTC));
    filter(sql, parameters, "event_type", query.eventType());
    filter(sql, parameters, "resource", query.resource());
    filter(sql, parameters, "actor", query.actor());
    filter(sql, parameters, "correlation_id", query.correlationId());
    if (query.after() != null) {
      sql.append(" AND (occurred_at,id)<(?,?)");
      parameters.add(OffsetDateTime.ofInstant(query.after().occurredAt(), ZoneOffset.UTC));
      parameters.add(query.after().id());
    }
    sql.append(" ORDER BY occurred_at DESC,id DESC LIMIT ?");
    parameters.add(query.limit() + 1);
    return transactions.execute(
        status ->
            jdbc.query(
                sql.toString(),
                (row, index) -> {
                  try {
                    return new AuditEventView(
                        row.getObject("id", UUID.class),
                        row.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                        row.getObject("received_at", OffsetDateTime.class).toInstant(),
                        json.readValue(row.getString("payload"), SecurityEvent.class));
                  } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
                    throw new IllegalStateException("Invalid stored audit envelope.");
                  }
                },
                parameters.toArray()));
  }

  private static void filter(
      StringBuilder sql, List<Object> parameters, String column, String value) {
    if (value != null) {
      // Column names are private constants, never request input.
      sql.append(" AND ").append(column).append("=?");
      parameters.add(value);
    }
  }
}

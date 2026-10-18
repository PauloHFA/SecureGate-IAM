package com.securegate.iam.audit.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securegate.iam.audit.application.port.AuditRecord;
import com.securegate.iam.audit.application.port.AuditTrail;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public class JdbcAuditTrail implements AuditTrail {
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;

  public JdbcAuditTrail(JdbcTemplate jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  @Override
  public void record(AuditRecord event) {
    try {
      jdbc.update(
          "INSERT INTO iam_audit.security_events (id,actor,event_type,resource,correlation_id,details) VALUES (?,?,?,?,?,?::jsonb)",
          UUID.randomUUID(),
          event.context().actor(),
          event.type().value(),
          event.resource(),
          event.context().correlationId(),
          json.writeValueAsString(event.details()));
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Audit serialization failed");
    }
  }
}

package com.securegate.iam.audit.application.port;

import com.securegate.events.SecurityEventType;
import com.securegate.iam.shared.application.port.OperationContext;
import java.util.Map;
import java.util.Objects;

public record AuditRecord(
    SecurityEventType type,
    String resource,
    OperationContext context,
    Map<String, String> details) {
  public AuditRecord {
    Objects.requireNonNull(type);
    Objects.requireNonNull(resource);
    Objects.requireNonNull(context);
    details = Map.copyOf(details);
  }
}

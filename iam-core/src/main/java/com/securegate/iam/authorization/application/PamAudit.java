package com.securegate.iam.authorization.application;

import com.securegate.events.SecurityEventType;
import com.securegate.iam.audit.application.port.AuditRecord;
import com.securegate.iam.audit.application.port.AuditTrail;
import com.securegate.iam.shared.application.port.OperationContext;
import java.util.Map;
import java.util.UUID;

/**
 * Record only server-owned operation/reason codes and an identity verified by the token resolver.
 */
public final class PamAudit {
  private final AuditTrail audit;

  public PamAudit(AuditTrail audit) {
    this.audit = audit;
  }

  public void denied(
      UUID actor,
      String correlation,
      ResourceAccessService.Operation operation,
      String code,
      Stage stage) {
    if (actor == null || operation == ResourceAccessService.Operation.ACCOUNT_READ) {
      return;
    }
    String reason =
        switch (code) {
          case "mfa_required" -> "MFA_REQUIRED";
          case "rate_limited" -> "RATE_LIMITED";
          case "request_conflict" -> "REQUEST_CONFLICT";
          case "request_unavailable" -> "REQUEST_UNAVAILABLE";
          default -> "ACCESS_DENIED";
        };
    audit.record(
        new AuditRecord(
            new SecurityEventType("pam.access.denied"),
            "database:diagnostics",
            new OperationContext(actor.toString(), correlation),
            Map.of("operation", operation.name(), "reason", reason, "stage", stage.name())));
  }

  public enum Stage {
    AUTHORIZATION,
    CLAIM,
    CONFIRMATION
  }
}

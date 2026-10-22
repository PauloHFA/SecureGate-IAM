package com.securegate.iam.audit.application;

public final class AuditQueryUnavailableException extends RuntimeException {
  public AuditQueryUnavailableException() {
    super("Audit query unavailable.");
  }
}

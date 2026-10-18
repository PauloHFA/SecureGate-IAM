package com.securegate.iam.audit.application.port;

public interface AuditTrail {
  void record(AuditRecord record);
}

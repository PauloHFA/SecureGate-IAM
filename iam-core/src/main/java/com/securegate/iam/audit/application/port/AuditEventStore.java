package com.securegate.iam.audit.application.port;

import com.securegate.events.SecurityEvent;

/** Independent consumer-side storage; never writes another producer fact or Outbox entry. */
public interface AuditEventStore {
  Result append(SecurityEvent event);

  enum Result {
    APPENDED,
    DUPLICATE
  }
}

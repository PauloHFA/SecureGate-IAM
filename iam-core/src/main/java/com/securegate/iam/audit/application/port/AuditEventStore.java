package com.securegate.iam.audit.application.port;

import com.securegate.events.SecurityEvent;
import java.util.List;

/** Independent consumer-side storage; never writes another producer fact or Outbox entry. */
public interface AuditEventStore {
  Result append(SecurityEvent event);

  /** Atomic batch: all results are returned only after commit; failures roll back every row. */
  List<Result> appendBatch(List<SecurityEvent> events);

  enum Result {
    APPENDED,
    DUPLICATE
  }
}

package com.securegate.iam.audit.application;

/**
 * A new fact cannot be added after the month has been archived. Exact replays remain duplicates.
 */
public final class ArchivedAuditMonthException extends RuntimeException {
  public ArchivedAuditMonthException() {
    super("New event targets an archived audit month.");
  }
}

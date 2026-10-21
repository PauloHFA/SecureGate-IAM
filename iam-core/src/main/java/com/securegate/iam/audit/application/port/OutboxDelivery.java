package com.securegate.iam.audit.application.port;

import java.util.Optional;
import java.util.UUID;

/** Each call commits independently. No database transaction is held across broker I/O. */
public interface OutboxDelivery {
  Optional<Claim> claim(int leaseSeconds);

  boolean published(Claim claim);

  boolean retry(Claim claim, EventTransport.Outcome failure, int delaySeconds);

  record Claim(
      UUID eventId,
      String eventType,
      int schemaVersion,
      String payload,
      int attempts,
      UUID claimId,
      String traceParent) {
    public Claim(
        UUID eventId,
        String eventType,
        int schemaVersion,
        String payload,
        int attempts,
        UUID claimId) {
      this(eventId, eventType, schemaVersion, payload, attempts, claimId, null);
    }

    public Claim {
      traceParent = OutboxTraceParent.safeOrNull(traceParent);
    }

    @Override
    public String toString() {
      return "OutboxClaim[eventId=" + eventId + ", attempts=" + attempts + ", payload=redacted]";
    }
  }
}

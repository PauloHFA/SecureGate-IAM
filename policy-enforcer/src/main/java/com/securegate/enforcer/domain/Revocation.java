package com.securegate.enforcer.domain;

import com.securegate.events.SecurityEvent;
import java.time.Instant;
import java.util.UUID;

/** Terminal facts only: no event can clear a previous revocation. */
public record Revocation(UUID eventId, Kind kind, String target, long expiresAtMillis) {
  public enum Kind {
    JTI,
    AUTHORIZATION,
    SID
  }

  public Revocation {
    if (eventId == null
        || kind == null
        || target == null
        || target.isBlank()
        || target.length() > 4096
        || expiresAtMillis < 0
        || expiresAtMillis > 253402300799999L
        || (kind == Kind.SID) != (expiresAtMillis == 0)) {
      throw new IllegalArgumentException("Invalid revocation.");
    }
  }

  public static Revocation from(SecurityEvent event) {
    if (!"iam-core".equals(event.producer())) {
      throw new IllegalArgumentException("Unsupported event producer.");
    }
    var data = event.data();
    return switch (event.eventType()) {
      case "session.terminated" -> new Revocation(event.eventId(), Kind.SID, data.get("sid"), 0);
      case "token.revoked" -> {
        boolean individual = "ACCESS_TOKEN".equals(data.get("target"));
        long deadline = Math.addExact(Instant.parse(data.get("expiresAt")).toEpochMilli(), 60000);
        yield new Revocation(
            event.eventId(),
            individual ? Kind.JTI : Kind.AUTHORIZATION,
            data.get(individual ? "jti" : "authorizationId"),
            deadline);
      }
      default -> throw new IllegalArgumentException("Unsupported revocation event.");
    };
  }

  @Override
  public String toString() {
    return "Revocation[kind=" + kind + ", target=redacted]";
  }
}

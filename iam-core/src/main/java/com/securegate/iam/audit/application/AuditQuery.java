package com.securegate.iam.audit.application;

import com.securegate.events.SecurityEventType;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** Inclusive/exclusive UTC window and exact filters; no offsets or arbitrary sort/SQL. */
public record AuditQuery(
    Instant from,
    Instant to,
    String eventType,
    String resource,
    String actor,
    String correlationId,
    int limit,
    Position after) {
  public record Position(Instant occurredAt, UUID id) {
    public Position {
      if (occurredAt == null || id == null || occurredAt.getNano() % 1000 != 0) {
        throw new IllegalArgumentException("Invalid audit cursor position.");
      }
    }
  }

  public AuditQuery {
    if (from == null
        || to == null
        || from.isBefore(Instant.EPOCH)
        || !from.isBefore(to)
        || to.isAfter(Instant.parse("9998-01-01T00:00:00Z"))
        || Duration.between(from, to).compareTo(Duration.ofDays(31)) > 0
        || from.getNano() % 1000 != 0
        || to.getNano() % 1000 != 0
        || limit < 1
        || limit > 100) {
      throw new IllegalArgumentException("Invalid audit query bounds.");
    }
    if (eventType != null) {
      new SecurityEventType(eventType);
    }
    text(resource, 100);
    text(actor, 200);
    text(correlationId, 64);
    if (after != null && (after.occurredAt().isBefore(from) || !after.occurredAt().isBefore(to))) {
      throw new IllegalArgumentException("Audit cursor outside query window.");
    }
  }

  private static void text(String value, int max) {
    if (value != null
        && (value.isBlank()
            || value.length() > max
            || value.chars().anyMatch(Character::isISOControl))) {
      throw new IllegalArgumentException("Invalid audit query filter.");
    }
  }
}

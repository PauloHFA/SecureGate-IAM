package com.securegate.events;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Versioned transport contract. Values must be metadata, never credentials or raw tokens. */
public record SecurityEvent(
    UUID eventId,
    String eventType,
    int schemaVersion,
    Instant occurredAt,
    String producer,
    String correlationId,
    String actor,
    Map<String, String> context,
    Map<String, String> data) {
  public static final int CURRENT_VERSION = 1;

  public SecurityEvent {
    if (eventId == null
        || occurredAt == null
        || occurredAt.isBefore(Instant.EPOCH)
        || schemaVersion != CURRENT_VERSION) {
      throw new IllegalArgumentException("Invalid security event header.");
    }
    new SecurityEventType(eventType);
    text(producer, 64);
    if (!producer.matches("[a-z][a-z0-9-]*")) {
      throw new IllegalArgumentException("Invalid event producer.");
    }
    text(correlationId, 64);
    text(actor, 200);
    context = metadata(context);
    data = metadata(data);
    text(context.get("resource"), 100);
    SecurityEventData.validate(eventType, data);
  }

  private static Map<String, String> metadata(Map<String, String> values) {
    if (values == null || values.size() > 32) {
      throw new IllegalArgumentException("Invalid event metadata.");
    }
    values.forEach(
        (key, value) -> {
          if (key == null || !key.matches("[a-zA-Z][a-zA-Z0-9_]{0,63}")) {
            throw new IllegalArgumentException("Invalid event metadata key.");
          }
          text(value, 4096);
        });
    return Map.copyOf(values);
  }

  private static void text(String value, int maximum) {
    if (value == null
        || value.isBlank()
        || value.length() > maximum
        || value.chars().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("Invalid event metadata value.");
    }
  }

  @Override
  public String toString() {
    return "SecurityEvent[eventId="
        + eventId
        + ", eventType="
        + eventType
        + ", schemaVersion="
        + schemaVersion
        + ", metadata=redacted]";
  }
}

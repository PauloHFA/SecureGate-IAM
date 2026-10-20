package com.securegate.iam.audit.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Opaque continuation, bound to filters. Not a credential or an integrity/authenticity signature.
 */
final class AuditCursor {
  static String encode(AuditQuery query, AuditQuery.Position position) {
    String value = "1|" + position.occurredAt() + "|" + position.id() + "|" + fingerprint(query);
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  static AuditQuery.Position decode(String cursor, AuditQuery query) {
    try {
      if (cursor == null || cursor.length() > 256 || !cursor.matches("[A-Za-z0-9_-]+")) {
        throw new IllegalArgumentException();
      }
      byte[] decoded = Base64.getUrlDecoder().decode(cursor);
      if (!Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(cursor)) {
        throw new IllegalArgumentException();
      }
      String[] fields = new String(decoded, StandardCharsets.UTF_8).split("\\|", -1);
      if (fields.length != 4
          || !"1".equals(fields[0])
          || !fingerprint(query).equals(fields[3])
          || !fields[2].matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
        throw new IllegalArgumentException();
      }
      return new AuditQuery.Position(Instant.parse(fields[1]), UUID.fromString(fields[2]));
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException("Invalid audit cursor.");
    }
  }

  private static String fingerprint(AuditQuery query) {
    String filters =
        String.join(
            "\n",
            query.from().toString(),
            query.to().toString(),
            value(query.eventType()),
            value(query.resource()),
            value(query.actor()),
            value(query.correlationId()));
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(filters.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("SHA-256 unavailable.");
    }
  }

  private static String value(String value) {
    return value == null ? "" : value;
  }

  private AuditCursor() {}
}

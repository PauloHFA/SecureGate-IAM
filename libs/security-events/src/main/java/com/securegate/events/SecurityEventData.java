package com.securegate.events;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Required data for the six integration events; other audit event types retain their own data. */
public final class SecurityEventData {
  private SecurityEventData() {}

  public static void validate(String type, Map<String, String> data) {
    Set<String> allowed =
        switch (type) {
          case "identity.user.created",
              "identity.user.updated",
              "identity.user.statuschanged",
              "identity.user.passwordchanged" ->
              Set.of("status", "version");
          case "policy.changed" ->
              Set.of("policyId", "version", "operation", "enabled", "resource", "action");
          case "user.locked" -> Set.of("userId", "version", "status", "reason");
          case "session.terminated" -> Set.of("userId", "version", "sid", "reason");
          case "auth.login.failed" ->
              Set.of("attemptId", "reason", "userId", "version", "remoteAddress");
          case "auth.login.suspicious" ->
              Set.of("userId", "version", "sid", "riskScore", "reasons", "remoteAddress");
          case "token.revoked" ->
              Set.of(
                  "authorizationId",
                  "registeredClientId",
                  "target",
                  "reason",
                  "expiresAt",
                  "jti",
                  "userId",
                  "version",
                  "sid");
          default -> null;
        };
    if (allowed != null && !allowed.containsAll(data.keySet())) {
      throw new IllegalArgumentException("Unexpected integration event field.");
    }
    if (allowed != null && data.containsKey("remoteAddress")) {
      numericAddress(data.get("remoteAddress"));
    }
    switch (type) {
      case "identity.user.created",
          "identity.user.updated",
          "identity.user.statuschanged",
          "identity.user.passwordchanged" -> {
        version(data);
        oneOf(data, "status", "ACTIVE", "LOCKED", "DISABLED");
      }
      case "policy.changed" -> {
        uuid(data, "policyId");
        version(data);
        oneOf(data, "operation", "CREATED", "UPDATED");
        oneOf(data, "enabled", "true", "false");
        required(data, "resource");
        required(data, "action");
      }
      case "user.locked" -> {
        uuid(data, "userId");
        version(data);
        oneOf(data, "status", "LOCKED");
        oneOf(data, "reason", "ADMINISTRATIVE");
      }
      case "auth.login.failed" -> {
        if (data.containsKey("userId") != data.containsKey("version")) {
          throw new IllegalArgumentException("Incomplete attempted identity.");
        }
        uuid(data, "attemptId");
        oneOf(data, "reason", "CREDENTIALS_OR_ACCOUNT_REJECTED");
        if (data.containsKey("userId")) {
          uuid(data, "userId");
          version(data);
        }
      }
      case "auth.login.suspicious" -> {
        uuid(data, "userId");
        uuid(data, "sid");
        version(data);
        int score = Integer.parseInt(required(data, "riskScore"));
        if (score < 70 || score > 100) {
          throw new IllegalArgumentException("Invalid suspicious login score.");
        }
        required(data, "reasons");
      }
      case "session.terminated" -> {
        uuid(data, "userId");
        uuid(data, "sid");
        version(data);
        oneOf(data, "reason", "BROWSER_CLOSED");
      }
      case "token.revoked" -> {
        if ((data.containsKey("version") || data.containsKey("sid"))
            && !data.containsKey("userId")) {
          throw new IllegalArgumentException("Incomplete revoked identity.");
        }
        required(data, "authorizationId");
        required(data, "registeredClientId");
        oneOf(data, "target", "ACCESS_TOKEN", "AUTHORIZATION");
        oneOf(data, "reason", "CLIENT_REVOCATION", "REFRESH_REUSE");
        Instant.parse(required(data, "expiresAt"));
        if ("ACCESS_TOKEN".equals(data.get("target"))) {
          required(data, "jti");
        }
        if (data.containsKey("userId")) {
          uuid(data, "userId");
          uuid(data, "sid");
          version(data);
        }
      }
      default -> {
        /* Existing audit types are not commands for the enforcer. */
      }
    }
  }

  /** Numeric literals only, with no DNS lookup of hostnames. */
  public static String numericAddress(String literal) {
    if (literal == null
        || literal.length() > 45
        || !literal.matches("[0-9a-fA-F:.]+")
        || !(literal.contains(":") || literal.contains("."))) {
      throw new IllegalArgumentException("Invalid observed address.");
    }
    if (!literal.contains(":")) {
      var parts = literal.split("\\.", -1);
      if (parts.length != 4) {
        throw new IllegalArgumentException("Invalid observed address.");
      }
      for (String part : parts) {
        if (!part.matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(part) > 255) {
          throw new IllegalArgumentException("Invalid observed address.");
        }
      }
    }
    try {
      return InetAddress.getByName(literal).getHostAddress();
    } catch (UnknownHostException invalid) {
      throw new IllegalArgumentException("Invalid observed address.");
    }
  }

  private static String required(Map<String, String> data, String key) {
    String value = data.get(key);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Missing integration event field.");
    }
    return value;
  }

  private static void uuid(Map<String, String> data, String key) {
    String value = required(data, key);
    if (!UUID.fromString(value).toString().equals(value)) {
      throw new IllegalArgumentException("Invalid event identifier.");
    }
  }

  private static void version(Map<String, String> data) {
    String value = required(data, "version");
    if (!value.matches("0|[1-9][0-9]{0,18}") || Long.parseLong(value) < 0) {
      throw new IllegalArgumentException("Invalid entity version.");
    }
  }

  private static void oneOf(Map<String, String> data, String key, String... values) {
    String value = required(data, key);
    for (String allowed : values) {
      if (allowed.equals(value)) {
        return;
      }
    }
    throw new IllegalArgumentException("Invalid integration event value.");
  }
}

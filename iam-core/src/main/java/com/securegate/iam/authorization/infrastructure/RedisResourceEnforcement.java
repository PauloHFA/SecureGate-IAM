package com.securegate.iam.authorization.infrastructure;

import com.securegate.events.SecurityEventData;
import com.securegate.iam.authorization.application.port.ResourceEnforcement;
import com.securegate.iam.policy.application.port.PolicyDecisions;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/** Read-only checks against the 7A/7B protocol. All failures escape as unavailable, never ALLOW. */
public final class RedisResourceEnforcement implements ResourceEnforcement {
  private final StringRedisTemplate redis;
  private final MeterRegistry meters;
  private final DefaultRedisScript<List> read =
      new DefaultRedisScript<>(
          "local values={} for i,key in ipairs(KEYS) do values[i]=redis.call('GET',key) or false end return values",
          List.class);

  public RedisResourceEnforcement(StringRedisTemplate redis, MeterRegistry meters) {
    this.redis = redis;
    this.meters = meters;
  }

  @Override
  public boolean tokenAllowed(
      UUID user, long version, UUID sid, String jti, String authorizationId) {
    return measured(
        "token",
        () -> {
          if (user == null
              || version < 0
              || sid == null
              || jti == null
              || jti.isBlank()
              || authorizationId == null
              || authorizationId.isBlank()) {
            return false;
          }
          var revoked =
              values(
                  List.of(
                      key("revocation", "jti", jti),
                      key("revocation", "sid", sid.toString()),
                      key("revocation", "authorization", authorizationId)));
          boolean denied = false;
          for (int i = 0; i < revoked.size(); i++) {
            String value = value(revoked.get(i));
            if (value != null) {
              deadline(value, i == 1);
              denied = true;
            }
          }
          if (denied) {
            return false;
          }
          String identity =
              value(values(List.of(key("block-policy", "identity", user.toString()))).getFirst());
          if (identity == null) {
            return true;
          }
          if (!identity.matches("[0-9]{19}\\|(ACTIVE|LOCKED|DISABLED)")) {
            throw new IllegalStateException();
          }
          long current = Long.parseLong(identity.substring(0, 19));
          return current < version || (current == version && identity.endsWith("|ACTIVE"));
        });
  }

  @Override
  public boolean requestAllowed(UUID user, long version, String remoteAddress) {
    return measured(
        "request",
        () -> {
          if (user == null || version < 0 || remoteAddress == null) {
            throw new IllegalStateException();
          }
          String address = SecurityEventData.numericAddress(remoteAddress);
          var blocked =
              values(
                  List.of(
                      key("block-policy", "blocked:account", user + ":" + version),
                      key("block-policy", "blocked:ip", address)));
          boolean denied = false;
          for (Object entry : blocked) {
            String value = value(entry);
            if (value != null) {
              deadline(value, false);
              denied = true;
            }
          }
          return !denied;
        });
  }

  @Override
  public boolean policiesCurrent(List<PolicyDecisions.Applied> policies) {
    return measured(
        "policy",
        () -> {
          if (policies.size() > 512) {
            throw new IllegalStateException();
          }
          if (policies.isEmpty()) {
            return true;
          }
          var values =
              values(
                  policies.stream()
                      .map(policy -> key("block-policy", "policy", policy.id().toString()))
                      .toList());
          for (int i = 0; i < policies.size(); i++) {
            String watermark = value(values.get(i));
            if (watermark == null) {
              continue;
            }
            if (!watermark.matches("[0-9]{19}\\|(true|false)\\|[a-f0-9]{64}")) {
              throw new IllegalStateException();
            }
            long version = Long.parseLong(watermark.substring(0, 19));
            if (version > policies.get(i).version()
                || (version == policies.get(i).version() && watermark.startsWith("false|", 20))) {
              return false;
            }
          }
          return true;
        });
  }

  private List<?> values(List<String> keys) {
    var result = redis.execute(read, keys);
    if (result == null || result.size() != keys.size()) {
      throw new IllegalStateException();
    }
    return result;
  }

  private static String value(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof String text) {
      return text;
    }
    throw new IllegalStateException();
  }

  private static void deadline(String value, boolean permanentAllowed) {
    if (value.equals("0") && permanentAllowed) {
      return;
    }
    if (!value.matches("[1-9][0-9]{0,14}") || Long.parseLong(value) > 253402300799999L) {
      throw new IllegalStateException();
    }
  }

  private boolean measured(String operation, BooleanSupplier check) {
    final boolean allowed;
    try {
      allowed = check.getAsBoolean();
    } catch (RuntimeException unavailable) {
      meters
          .counter("iam.resources.enforcement", "operation", operation, "result", "unavailable")
          .increment();
      throw new Unavailable();
    }
    meters
        .counter(
            "iam.resources.enforcement",
            "operation",
            operation,
            "result",
            allowed ? "allowed" : "denied")
        .increment();
    return allowed;
  }

  public static String key(String group, String kind, String subject) {
    return com.securegate.events.SecurityProjectionKeys.key(group, kind, subject);
  }

  public static final class Unavailable extends RuntimeException {
    public Unavailable() {
      super("Distributed resource check unavailable.");
    }
  }
}

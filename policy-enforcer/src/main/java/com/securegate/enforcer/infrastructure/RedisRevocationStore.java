package com.securegate.enforcer.infrastructure;

import com.securegate.enforcer.application.port.RevocationStore;
import com.securegate.enforcer.domain.Revocation;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

public final class RedisRevocationStore implements RevocationStore {
  private final StringRedisTemplate redis;
  private final DefaultRedisScript<Long> script = new DefaultRedisScript<>();

  public RedisRevocationStore(StringRedisTemplate redis) {
    this.redis = redis;
    script.setLocation(new ClassPathResource("redis/apply-revocation.lua"));
    script.setResultType(Long.class);
  }

  @Override
  public Result apply(Revocation revocation, String fingerprint) {
    if (fingerprint == null || !fingerprint.matches("[a-f0-9]{64}")) {
      throw new IllegalArgumentException("Invalid fingerprint.");
    }
    Long result =
        redis.execute(
            script,
            List.of(
                "securegate:{revocation}:event:" + revocation.eventId(),
                key(revocation.kind(), revocation.target())),
            fingerprint,
            Long.toString(revocation.expiresAtMillis()));
    if (result == null) {
      throw new IllegalStateException("Missing revocation result.");
    }
    return switch (result.intValue()) {
      case 1 -> Result.APPLIED;
      case 0 -> Result.DUPLICATE;
      case -1 -> Result.CONFLICT;
      default -> throw new IllegalStateException("Invalid revocation result.");
    };
  }

  public static String key(Revocation.Kind kind, String target) {
    return com.securegate.events.SecurityProjectionKeys.key(
        "revocation", kind.name().toLowerCase(java.util.Locale.ROOT), target);
  }

  public static String fingerprint(byte[] body) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable.");
    }
  }
}

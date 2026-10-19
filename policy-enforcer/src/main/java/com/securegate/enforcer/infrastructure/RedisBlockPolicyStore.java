package com.securegate.enforcer.infrastructure;

import com.securegate.enforcer.application.port.BlockPolicyStore;
import com.securegate.enforcer.domain.BlockPolicyEffect;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

public final class RedisBlockPolicyStore implements BlockPolicyStore {
  private final StringRedisTemplate redis;
  private final DefaultRedisScript<Long> script = new DefaultRedisScript<>();
  private final int windowSeconds, blockSeconds, riskSeconds, accountThreshold, ipThreshold;

  public RedisBlockPolicyStore(
      StringRedisTemplate redis,
      int windowSeconds,
      int blockSeconds,
      int riskSeconds,
      int accountThreshold,
      int ipThreshold) {
    if (windowSeconds < 1
        || windowSeconds > 3600
        || blockSeconds < 1
        || blockSeconds > 3600
        || riskSeconds < 1
        || riskSeconds > 3600
        || accountThreshold < 1
        || accountThreshold > 1000
        || ipThreshold < 1
        || ipThreshold > 1000) {
      throw new IllegalArgumentException("Invalid limits.");
    }
    this.redis = redis;
    this.windowSeconds = windowSeconds;
    this.blockSeconds = blockSeconds;
    this.riskSeconds = riskSeconds;
    this.accountThreshold = accountThreshold;
    this.ipThreshold = ipThreshold;
    script.setLocation(new ClassPathResource("redis/apply-block-policy.lua"));
    script.setResultType(Long.class);
  }

  @Override
  public Result apply(BlockPolicyEffect effect, String fingerprint) {
    if (fingerprint == null || !fingerprint.matches("[a-f0-9]{64}")) {
      throw new IllegalArgumentException("Invalid fingerprint.");
    }
    String state = effect.state();
    if (effect.kind() == BlockPolicyEffect.Kind.POLICY) {
      int separator = state.indexOf('|');
      state = state.substring(0, separator) + "|" + hash(state.substring(separator + 1));
    }
    String primary =
        effect.kind() == BlockPolicyEffect.Kind.FAILURE
            ? "attempts:account"
            : effect.kind().name().toLowerCase(Locale.ROOT);
    Long result =
        redis.execute(
            script,
            List.of(
                "securegate:{block-policy}:event:" + effect.eventId(),
                key(primary, effect.subject()),
                key("attempts:ip", effect.network()),
                key("blocked:account", effect.subject()),
                key("blocked:ip", effect.network())),
            fingerprint,
            effect.kind().name(),
            String.format(Locale.ROOT, "%019d", Long.parseLong(effect.version())),
            state,
            Long.toString(effect.occurredAtMillis()),
            effect.attemptId(),
            effect.subject().isEmpty() ? "0" : "1",
            effect.network().isEmpty() ? "0" : "1",
            Integer.toString(windowSeconds * 1000),
            Integer.toString(blockSeconds * 1000),
            Integer.toString(riskSeconds * 1000),
            Integer.toString(accountThreshold),
            Integer.toString(ipThreshold));
    if (result == null) {
      throw new IllegalStateException("Missing projection result.");
    }
    return switch (result.intValue()) {
      case 1 -> Result.APPLIED;
      case 0 -> Result.DUPLICATE;
      case -1 -> Result.CONFLICT;
      default -> throw new IllegalStateException("Invalid projection result.");
    };
  }

  public static String key(String kind, String subject) {
    return com.securegate.events.SecurityProjectionKeys.key("block-policy", kind, subject);
  }

  private static String hash(String value) {
    return RedisRevocationStore.fingerprint(value.getBytes(StandardCharsets.UTF_8));
  }
}

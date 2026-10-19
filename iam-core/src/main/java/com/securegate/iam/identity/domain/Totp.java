package com.securegate.iam.identity.domain;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.OptionalLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** RFC 6238: HMAC-SHA1, six digits, 30 seconds, a fixed +/- one-step window. */
public final class Totp {
  private Totp() {}

  /**
   * Returns the newest matching counter strictly after lastAcceptedCounter (-1 for none). The
   * caller must atomically persist that counter with acceptance, under an account lock. This
   * function alone does not consume a code or rate-limit guesses.
   */
  public static OptionalLong match(
      TotpSecret secret, String code, Instant now, long lastAcceptedCounter) {
    if (secret == null || now == null || now.getEpochSecond() < 0 || lastAcceptedCounter < -1) {
      throw new IllegalArgumentException("Invalid TOTP verification context.");
    }
    if (code == null || !code.matches("[0-9]{6}")) {
      return OptionalLong.empty();
    }
    long current = now.getEpochSecond() / 30;
    byte[] input = code.getBytes(StandardCharsets.US_ASCII);
    byte[] key = secret.keyBytes();
    long matched = -1;
    try {
      Mac mac = Mac.getInstance("HmacSHA1");
      mac.init(new SecretKeySpec(key, "HmacSHA1"));
      for (long counter = Math.max(0, current - 1); counter <= current + 1; counter++) {
        byte[] digest = mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(counter).array());
        int offset = digest[digest.length - 1] & 15;
        int truncated = ByteBuffer.wrap(digest, offset, Integer.BYTES).getInt() & 0x7fffffff;
        byte[] expected =
            String.format(Locale.ROOT, "%06d", truncated % 1_000_000)
                .getBytes(StandardCharsets.US_ASCII);
        boolean equal = MessageDigest.isEqual(input, expected);
        Arrays.fill(digest, (byte) 0);
        Arrays.fill(expected, (byte) 0);
        if (equal && counter > lastAcceptedCounter) {
          matched = counter;
        }
      }
      return matched < 0 ? OptionalLong.empty() : OptionalLong.of(matched);
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("TOTP algorithm unavailable.");
    } finally {
      Arrays.fill(key, (byte) 0);
      Arrays.fill(input, (byte) 0);
    }
  }
}

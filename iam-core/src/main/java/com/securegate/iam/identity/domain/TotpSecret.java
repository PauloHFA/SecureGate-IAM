package com.securegate.iam.identity.domain;

import java.security.SecureRandom;
import java.util.Arrays;

/** A 160-bit TOTP seed, encoded as canonical unpadded Base32. Never log its value. */
public final class TotpSecret {
  private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
  private static final SecureRandom RANDOM = new SecureRandom();
  private final String encoded;

  private TotpSecret(String encoded) {
    this.encoded = encoded;
  }

  public static TotpSecret parse(String encoded) {
    if (encoded == null || !encoded.matches("[A-Z2-7]{32}")) {
      throw new IllegalArgumentException("Invalid TOTP secret.");
    }
    return new TotpSecret(encoded);
  }

  public static TotpSecret generate() {
    byte[] bytes = new byte[20];
    RANDOM.nextBytes(bytes);
    try {
      StringBuilder encoded = new StringBuilder(32);
      int buffer = 0;
      int bits = 0;
      for (byte value : bytes) {
        buffer = (buffer << 8) | (value & 255);
        bits += 8;
        while (bits >= 5) {
          bits -= 5;
          encoded.append(ALPHABET.charAt((buffer >>> bits) & 31));
        }
      }
      return new TotpSecret(encoded.toString());
    } finally {
      Arrays.fill(bytes, (byte) 0);
    }
  }

  /** Only for delivery to the authenticator or encryption; never for logs or audit. */
  public String encoded() {
    return encoded;
  }

  byte[] keyBytes() {
    byte[] bytes = new byte[20];
    int buffer = 0;
    int bits = 0;
    int position = 0;
    for (int i = 0; i < encoded.length(); i++) {
      buffer = (buffer << 5) | ALPHABET.indexOf(encoded.charAt(i));
      bits += 5;
      if (bits >= 8) {
        bits -= 8;
        bytes[position++] = (byte) (buffer >>> bits);
      }
    }
    return bytes;
  }

  @Override
  public String toString() {
    return "TotpSecret[redacted]";
  }
}

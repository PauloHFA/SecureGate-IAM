package com.securegate.events;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Shared wire-key protocol for the enforcer writers and IAM readers, independent of Redis APIs. */
public final class SecurityProjectionKeys {
  private SecurityProjectionKeys() {}

  public static String key(String group, String kind, String subject) {
    if ((!"revocation".equals(group) && !"block-policy".equals(group))
        || kind == null
        || !kind.matches("[a-z]+(?::[a-z]+)?")
        || subject == null) {
      throw new IllegalArgumentException("Invalid projection key.");
    }
    try {
      return "securegate:{"
          + group
          + "}:"
          + kind
          + ":"
          + HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(subject.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable.");
    }
  }
}

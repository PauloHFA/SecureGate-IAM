package com.securegate.iam.audit.application.port;

import java.util.regex.Pattern;

/** Optional version-00 context, deliberately excluding baggage and vendor state. */
public final class OutboxTraceParent {
  private static final Pattern SAFE = Pattern.compile("00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]");

  public static String safeOrNull(String value) {
    return value != null
            && SAFE.matcher(value).matches()
            && !value.substring(3, 35).equals("0".repeat(32))
            && !value.substring(36, 52).equals("0".repeat(16))
        ? value
        : null;
  }

  private OutboxTraceParent() {}
}

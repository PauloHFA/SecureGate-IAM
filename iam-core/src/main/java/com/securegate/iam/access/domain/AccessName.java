package com.securegate.iam.access.domain;

import java.util.Locale;

final class AccessName {
  static String normalize(String name) {
    if (name == null) {
      throw new IllegalArgumentException("Name is required");
    }
    String value = name.trim().toUpperCase(Locale.ROOT);
    if (!value.matches("[A-Z][A-Z0-9_]{0,63}")) {
      throw new IllegalArgumentException("Name is invalid");
    }
    return value;
  }

  private AccessName() {}
}

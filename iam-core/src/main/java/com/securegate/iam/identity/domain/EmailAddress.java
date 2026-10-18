package com.securegate.iam.identity.domain;

import java.util.Locale;

public record EmailAddress(String value) {
  public EmailAddress {
    if (value == null) {
      throw new IllegalArgumentException("Email is required");
    }
    value = value.trim().toLowerCase(Locale.ROOT);
    if (value.length() > 254 || !value.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) {
      throw new IllegalArgumentException("Email is invalid");
    }
  }
}

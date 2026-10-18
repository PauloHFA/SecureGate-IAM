package com.securegate.iam.access.domain;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public record Permission(UUID id, String resource, String action) {
  public Permission {
    Objects.requireNonNull(id);
    resource = part(resource);
    action = part(action);
  }

  private static String part(String value) {
    if (value == null) {
      throw new IllegalArgumentException("Permission is required");
    }
    String result = value.trim().toLowerCase(Locale.ROOT);
    if (!result.matches("[a-z][a-z0-9_-]{0,63}")) {
      throw new IllegalArgumentException("Permission is invalid");
    }
    return result;
  }

  public String key() {
    return resource + ":" + action;
  }
}

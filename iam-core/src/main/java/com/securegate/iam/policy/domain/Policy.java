package com.securegate.iam.policy.domain;

import java.util.UUID;

public record Policy(
    UUID id, String name, int priority, boolean enabled, long version, PolicyRule document) {
  public Policy {
    if (id == null
        || name == null
        || !name.matches("[a-z][a-z0-9_-]{2,63}")
        || priority < 0
        || priority > 1000
        || version < 0
        || version == Long.MAX_VALUE
        || document == null) {
      throw new IllegalArgumentException("Invalid policy");
    }
  }

  public Policy replace(String name, int priority, boolean enabled, PolicyRule rule) {
    return new Policy(id, name, priority, enabled, version + 1, rule);
  }
}

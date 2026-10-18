package com.securegate.iam.policy.application;

import com.securegate.iam.policy.domain.Policy;
import java.util.UUID;

/** The JSON document crosses presentation as JSON text, with no domain dependency there. */
public record PolicyView(
    UUID id, String name, int priority, boolean enabled, long version, String document) {
  public static PolicyView from(Policy policy, String document) {
    return new PolicyView(
        policy.id(),
        policy.name(),
        policy.priority(),
        policy.enabled(),
        policy.version(),
        document);
  }
}

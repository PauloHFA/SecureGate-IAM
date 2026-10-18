package com.securegate.iam.policy.domain;

import java.util.Set;

public record EvaluationContext(
    String resource, String action, Set<String> roles, Set<String> requestedScopes) {
  public EvaluationContext {
    if (!PolicyRule.target(resource)
        || "*".equals(resource)
        || !PolicyRule.target(action)
        || "*".equals(action)
        || roles == null) {
      throw new IllegalArgumentException("Evaluation requires a concrete target");
    }
    PolicyRule.validateScopes(requestedScopes, false);
    roles = Set.copyOf(roles);
    requestedScopes = Set.copyOf(requestedScopes);
  }
}

package com.securegate.iam.policy.domain;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** A recommendation; MFA_REQUIRED is never proof that a factor has been satisfied. */
public record PolicyDecision(
    PolicyEffect effect,
    List<String> reasons,
    List<AppliedPolicy> appliedPolicies,
    Set<String> allowedScopes,
    int maxAccessTokenTtlSeconds,
    boolean mfaRequired) {
  public PolicyDecision {
    reasons = List.copyOf(reasons);
    appliedPolicies = List.copyOf(appliedPolicies);
    allowedScopes = Set.copyOf(allowedScopes);
  }

  public static PolicyDecision deny(String reason, List<AppliedPolicy> policies) {
    return new PolicyDecision(PolicyEffect.DENY, List.of(reason), policies, Set.of(), 0, false);
  }

  public record AppliedPolicy(UUID id, long version) {}
}

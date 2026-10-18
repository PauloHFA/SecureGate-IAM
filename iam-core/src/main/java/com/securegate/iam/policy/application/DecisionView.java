package com.securegate.iam.policy.application;

import com.securegate.iam.policy.domain.PolicyDecision;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public record DecisionView(
    String effect,
    List<String> reasons,
    List<AppliedPolicy> appliedPolicies,
    Set<String> allowedScopes,
    int maxAccessTokenTtlSeconds,
    boolean mfaRequired) {
  public static DecisionView from(PolicyDecision decision) {
    return new DecisionView(
        decision.effect().name(),
        decision.reasons(),
        decision.appliedPolicies().stream()
            .map(policy -> new AppliedPolicy(policy.id(), policy.version()))
            .toList(),
        decision.allowedScopes(),
        decision.maxAccessTokenTtlSeconds(),
        decision.mfaRequired());
  }

  public record AppliedPolicy(UUID id, long version) {}
}

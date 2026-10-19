package com.securegate.iam.policy.application;

import com.securegate.iam.policy.domain.PolicyDecision;
import com.securegate.iam.policy.domain.RiskAssessment;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public record DecisionView(
    String effect,
    List<String> reasons,
    List<AppliedPolicy> appliedPolicies,
    Set<String> allowedScopes,
    int maxAccessTokenTtlSeconds,
    boolean mfaRequired,
    RiskView risk,
    boolean simulation) {
  public static DecisionView from(
      PolicyDecision decision, RiskAssessment risk, boolean simulation) {
    return new DecisionView(
        decision.effect().name(),
        decision.reasons(),
        decision.appliedPolicies().stream()
            .map(policy -> new AppliedPolicy(policy.id(), policy.version()))
            .toList(),
        decision.allowedScopes(),
        decision.maxAccessTokenTtlSeconds(),
        decision.mfaRequired(),
        RiskView.from(risk),
        simulation);
  }

  public record AppliedPolicy(UUID id, long version) {}
}

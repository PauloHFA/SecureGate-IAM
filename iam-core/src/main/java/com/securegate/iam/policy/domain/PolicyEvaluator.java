package com.securegate.iam.policy.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

public final class PolicyEvaluator {
  public PolicyDecision evaluate(List<Policy> policies, EvaluationContext context) {
    var matched =
        policies.stream()
            .filter(Policy::enabled)
            .filter(policy -> policy.document().match(context) != AttributeCondition.Match.NO_MATCH)
            .sorted(Comparator.comparingInt(Policy::priority).reversed().thenComparing(Policy::id))
            .toList();
    var applied =
        matched.stream()
            .map(policy -> new PolicyDecision.AppliedPolicy(policy.id(), policy.version()))
            .toList();
    if (matched.isEmpty()) {
      return PolicyDecision.deny("NO_MATCH", applied);
    }
    if (matched.stream()
        .anyMatch(
            policy ->
                policy.document().effect() == PolicyEffect.DENY
                    && policy.document().match(context) == AttributeCondition.Match.MATCH)) {
      return PolicyDecision.deny("EXPLICIT_DENY", applied);
    }
    if (matched.stream()
        .anyMatch(
            policy -> policy.document().match(context) == AttributeCondition.Match.INDETERMINATE)) {
      return PolicyDecision.deny("INDETERMINATE_ATTRIBUTES", applied);
    }
    var scopes = new HashSet<>(context.requestedScopes());
    int ttl = 300;
    boolean limited = false;
    boolean mfa = false;
    for (var policy : matched) {
      var rule = policy.document();
      if (rule.effect() == PolicyEffect.ALLOW_LIMITED) {
        limited = true;
        scopes.retainAll(rule.allowedScopes());
        ttl = Math.min(ttl, rule.maxAccessTokenTtlSeconds());
      }
      mfa |= rule.effect() == PolicyEffect.ALLOW_WITH_MFA;
    }
    if (scopes.isEmpty()) {
      return PolicyDecision.deny("NO_PERMITTED_SCOPES", applied);
    }
    var reasons = new ArrayList<String>();
    if (mfa) {
      reasons.add("MFA_REQUIRED");
    }
    if (limited) {
      reasons.add("LIMITED");
    }
    if (reasons.isEmpty()) {
      reasons.add("PERMITTED");
    }
    return new PolicyDecision(
        mfa
            ? PolicyEffect.ALLOW_WITH_MFA
            : limited ? PolicyEffect.ALLOW_LIMITED : PolicyEffect.ALLOW,
        reasons,
        applied,
        scopes,
        ttl,
        mfa);
  }
}

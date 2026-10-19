package com.securegate.iam.policy.application.port;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Live evaluation only: callers cannot supply synthetic signals, roles or MFA flags. */
public interface PolicyDecisions {
  Decision decide(UUID userId, String resource, String action, Set<String> scopes, UUID contextId);

  record Applied(UUID id, long version) {}

  record Decision(
      boolean denied,
      Set<String> scopes,
      int ttlSeconds,
      boolean mfaRequired,
      List<Applied> policies,
      int riskScore) {
    public Decision {
      scopes = Set.copyOf(scopes);
      policies = List.copyOf(policies);
    }
  }
}

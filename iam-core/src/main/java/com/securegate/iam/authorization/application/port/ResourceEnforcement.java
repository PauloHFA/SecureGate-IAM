package com.securegate.iam.authorization.application.port;

import com.securegate.iam.policy.application.port.PolicyDecisions;
import java.util.List;
import java.util.UUID;

/**
 * Additional distributed deny signals; absence never replaces the live local grant/policy checks.
 */
public interface ResourceEnforcement {
  boolean tokenAllowed(UUID userId, long version, UUID sid, String jti, String authorizationId);

  boolean requestAllowed(UUID userId, long version, String remoteAddress);

  boolean policiesCurrent(List<PolicyDecisions.Applied> policies);

  ResourceEnforcement DISABLED =
      new ResourceEnforcement() {
        public boolean tokenAllowed(
            UUID userId, long version, UUID sid, String jti, String authorizationId) {
          return true;
        }

        public boolean requestAllowed(UUID userId, long version, String remoteAddress) {
          return true;
        }

        public boolean policiesCurrent(List<PolicyDecisions.Applied> policies) {
          return true;
        }
      };
}

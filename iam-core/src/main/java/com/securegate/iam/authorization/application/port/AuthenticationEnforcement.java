package com.securegate.iam.authorization.application.port;

import com.securegate.iam.policy.application.port.PolicyDecisions;
import java.util.List;
import java.util.UUID;

/** Additional deny signals and a requirement for real MFA, never an authentication proof. */
public interface AuthenticationEnforcement {
  boolean ipAllowed(String remoteAddress);

  boolean loginAllowed(UUID user, long version, String remoteAddress);

  boolean sessionAllowed(UUID user, long version, UUID sid);

  boolean authorizationAllowed(String authorizationId);

  boolean riskObserved(UUID sid);

  boolean policiesCurrent(List<PolicyDecisions.Applied> policies);

  AuthenticationEnforcement DISABLED =
      new AuthenticationEnforcement() {
        public boolean ipAllowed(String remoteAddress) {
          return true;
        }

        public boolean loginAllowed(UUID user, long version, String remoteAddress) {
          return true;
        }

        public boolean sessionAllowed(UUID user, long version, UUID sid) {
          return true;
        }

        public boolean authorizationAllowed(String authorizationId) {
          return true;
        }

        public boolean riskObserved(UUID sid) {
          return false;
        }

        public boolean policiesCurrent(List<PolicyDecisions.Applied> policies) {
          return true;
        }
      };
}

package com.securegate.iam.policy.domain;

import java.time.Instant;

/**
 * Facts must come from a trusted collector; simulations are explicitly separated by the service.
 */
public record RiskSignals(
    Trust deviceTrust,
    Trust networkTrust,
    Integer failedLoginAttempts,
    Sensitivity resourceSensitivity,
    Instant observedAt) {
  public enum Trust {
    TRUSTED,
    UNTRUSTED,
    UNKNOWN
  }

  public enum Sensitivity {
    PUBLIC,
    INTERNAL,
    CONFIDENTIAL,
    RESTRICTED,
    UNKNOWN
  }

  public RiskSignals {
    if (deviceTrust == null
        || networkTrust == null
        || resourceSensitivity == null
        || (failedLoginAttempts != null
            && (failedLoginAttempts < 0 || failedLoginAttempts > 1000))) {
      throw new IllegalArgumentException("Invalid risk signals");
    }
  }

  public static RiskSignals unknown(Instant now) {
    return new RiskSignals(Trust.UNKNOWN, Trust.UNKNOWN, null, Sensitivity.UNKNOWN, now);
  }
}

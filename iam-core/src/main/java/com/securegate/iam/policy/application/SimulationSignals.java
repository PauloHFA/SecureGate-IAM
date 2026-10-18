package com.securegate.iam.policy.application;

import com.securegate.iam.policy.domain.RiskSignals;
import java.time.Instant;

/** Synthetic administrative scenario, never a trusted evidence source for normal evaluation. */
public record SimulationSignals(
    String deviceTrust,
    String networkTrust,
    Integer failedLoginAttempts,
    String resourceSensitivity) {
  public RiskSignals at(Instant now) {
    return new RiskSignals(
        deviceTrust == null ? RiskSignals.Trust.UNKNOWN : RiskSignals.Trust.valueOf(deviceTrust),
        networkTrust == null ? RiskSignals.Trust.UNKNOWN : RiskSignals.Trust.valueOf(networkTrust),
        failedLoginAttempts,
        resourceSensitivity == null
            ? RiskSignals.Sensitivity.UNKNOWN
            : RiskSignals.Sensitivity.valueOf(resourceSensitivity),
        now);
  }
}

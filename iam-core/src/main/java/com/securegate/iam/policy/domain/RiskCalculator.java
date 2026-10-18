package com.securegate.iam.policy.domain;

import java.time.Instant;
import java.util.ArrayList;

/** Fixed arithmetic, no randomness, remote lookup, IP inference or user-agent trust. */
public final class RiskCalculator {
  public RiskAssessment assess(RiskSignals supplied, Instant now) {
    var reasons = new ArrayList<String>();
    var signals = supplied == null ? RiskSignals.unknown(null) : supplied;
    if (signals.observedAt() == null
        || signals.observedAt().isBefore(now.minusSeconds(300))
        || signals.observedAt().isAfter(now.plusSeconds(5))) {
      reasons.add("INVALID_SIGNAL_TIME");
      signals = RiskSignals.unknown(now);
    }
    var missing = new ArrayList<String>();
    int score = 0;
    if (signals.deviceTrust() != RiskSignals.Trust.TRUSTED) {
      score += 25;
      reasons.add(
          signals.deviceTrust() == RiskSignals.Trust.UNKNOWN
              ? "DEVICE_UNKNOWN"
              : "DEVICE_UNTRUSTED");
    }
    if (signals.deviceTrust() == RiskSignals.Trust.UNKNOWN) {
      missing.add("DEVICE_TRUST");
    }
    if (signals.networkTrust() != RiskSignals.Trust.TRUSTED) {
      score += 20;
      reasons.add(
          signals.networkTrust() == RiskSignals.Trust.UNKNOWN
              ? "NETWORK_UNKNOWN"
              : "NETWORK_UNTRUSTED");
    }
    if (signals.networkTrust() == RiskSignals.Trust.UNKNOWN) {
      missing.add("NETWORK_TRUST");
    }
    Integer attempts = signals.failedLoginAttempts();
    if (attempts == null) {
      score += 30;
      reasons.add("BEHAVIOR_UNKNOWN");
      missing.add("FAILED_LOGIN_ATTEMPTS");
    } else if (attempts > 0) {
      score += attempts >= 5 ? 30 : attempts >= 3 ? 20 : 10;
      reasons.add("RECENT_LOGIN_FAILURES");
    }
    score +=
        switch (signals.resourceSensitivity()) {
          case PUBLIC -> 0;
          case INTERNAL -> 5;
          case CONFIDENTIAL -> 15;
          case RESTRICTED, UNKNOWN -> 25;
        };
    if (signals.resourceSensitivity() == RiskSignals.Sensitivity.UNKNOWN) {
      reasons.add("RESOURCE_UNKNOWN");
      missing.add("RESOURCE_SENSITIVITY");
    } else if (signals.resourceSensitivity() != RiskSignals.Sensitivity.PUBLIC) {
      reasons.add("RESOURCE_SENSITIVITY");
    }
    if (reasons.isEmpty()) {
      reasons.add("KNOWN_LOW_RISK_CONTEXT");
    }
    return new RiskAssessment(
        score, RiskAssessment.Level.forScore(score), reasons, missing, signals);
  }
}

package com.securegate.iam.policy.domain;

import java.util.List;

public record RiskAssessment(
    int score,
    Level level,
    List<String> reasons,
    List<String> missingSignals,
    RiskSignals signals) {
  public RiskAssessment {
    if (score < 0 || score > 100 || level != Level.forScore(score) || signals == null) {
      throw new IllegalArgumentException("Invalid risk assessment");
    }
    reasons = List.copyOf(reasons);
    missingSignals = List.copyOf(missingSignals);
  }

  public enum Level {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL;

    public static Level forScore(int score) {
      if (score < 0 || score > 100) {
        throw new IllegalArgumentException("Invalid risk score");
      }
      return score < 30 ? LOW : score < 60 ? MEDIUM : score < 80 ? HIGH : CRITICAL;
    }
  }
}

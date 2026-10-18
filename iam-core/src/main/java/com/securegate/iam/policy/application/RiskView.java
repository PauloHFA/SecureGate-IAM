package com.securegate.iam.policy.application;

import com.securegate.iam.policy.domain.RiskAssessment;
import java.util.List;

public record RiskView(int score, String level, List<String> reasons, List<String> missingSignals) {
  public static RiskView from(RiskAssessment risk) {
    return new RiskView(risk.score(), risk.level().name(), risk.reasons(), risk.missingSignals());
  }
}

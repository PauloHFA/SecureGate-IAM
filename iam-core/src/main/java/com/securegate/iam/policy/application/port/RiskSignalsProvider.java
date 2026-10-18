package com.securegate.iam.policy.application.port;

import com.securegate.iam.policy.domain.RiskSignals;
import java.util.UUID;

/**
 * Future collectors must bind evidence to the evaluated subject/resource and its observation time.
 */
public interface RiskSignalsProvider {
  RiskSignals collect(UUID userId, String resource);
}

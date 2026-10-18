package com.securegate.iam.policy.infrastructure;

import com.securegate.iam.policy.application.port.RiskSignalsProvider;
import com.securegate.iam.policy.domain.RiskSignals;
import java.time.Clock;
import java.util.UUID;

/** No real device/network/behavior/resource collectors exist yet. Never fabricate safe evidence. */
public final class UnavailableRiskSignals implements RiskSignalsProvider {
  private final Clock clock;

  public UnavailableRiskSignals(Clock clock) {
    this.clock = clock;
  }

  @Override
  public RiskSignals collect(UUID userId, String resource) {
    return RiskSignals.unknown(clock.instant());
  }
}

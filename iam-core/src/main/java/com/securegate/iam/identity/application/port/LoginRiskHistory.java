package com.securegate.iam.identity.application.port;

import java.util.UUID;

public interface LoginRiskHistory {
  void recordFailure(String email);

  /** Null means observation is unavailable or the five-minute coverage window is incomplete. */
  Integer recentFailures(UUID userId);
}

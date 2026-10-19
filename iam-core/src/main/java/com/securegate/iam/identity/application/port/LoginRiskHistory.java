package com.securegate.iam.identity.application.port;

import java.util.UUID;

public interface LoginRiskHistory {
  void recordFailure(String email);

  /** Address must be the observed server connection, never an untrusted forwarding header. */
  void recordFailure(String email, String remoteAddress);

  /** Null means observation is unavailable or the five-minute coverage window is incomplete. */
  Integer recentFailures(UUID userId);
}

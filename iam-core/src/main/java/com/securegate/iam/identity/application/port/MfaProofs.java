package com.securegate.iam.identity.application.port;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Only trusted session IDs may be used by enforcement points. Caller flags are not evidence. */
public interface MfaProofs {
  Optional<Proof> current(UUID userId, long identityVersion, UUID sessionId);

  void revokeSession(UUID sessionId);

  record Proof(String method, Instant verifiedAt, Instant expiresAt) {}
}

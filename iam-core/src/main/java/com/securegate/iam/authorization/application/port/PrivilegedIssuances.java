package com.securegate.iam.authorization.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PrivilegedIssuances {
  boolean start(UUID requestId, Instant now, String correlationId);

  default boolean start(UUID requestId, Instant now) {
    return start(requestId, now, requestId.toString());
  }

  boolean finish(UUID requestId, String leaseId, Instant expiresAt);

  boolean abort(UUID requestId, String leaseId, Instant expiresAt);

  Optional<Entry> find(UUID requestId);

  List<Entry> pending(Instant now);

  boolean revoked(UUID requestId);

  boolean revoking(UUID requestId);

  boolean abandoned(UUID requestId, Instant cutoff);

  boolean recordExpiration(UUID requestId, Instant now);

  record Entry(
      UUID requestId, String state, String leaseId, Instant expiresAt, String originCorrelationId) {
    public Entry(UUID requestId, String state, String leaseId, Instant expiresAt) {
      this(requestId, state, leaseId, expiresAt, requestId.toString());
    }

    @Override
    public String toString() {
      return "PamIssuance[redacted]";
    }
  }
}

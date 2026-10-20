package com.securegate.iam.authorization.application.port;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface PrivilegedRequests {
  void insert(Entry entry);

  Optional<Entry> find(UUID id, UUID userId);

  boolean revoke(UUID id, UUID userId, Instant now);

  record Entry(
      UUID id,
      UUID userId,
      String target,
      String justification,
      Instant createdAt,
      Instant expiresAt,
      Instant revokedAt) {
    @Override
    public String toString() {
      return "PrivilegedRequest[redacted]";
    }
  }
}

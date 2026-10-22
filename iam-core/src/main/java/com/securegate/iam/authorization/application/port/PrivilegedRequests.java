package com.securegate.iam.authorization.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PrivilegedRequests {
  void insert(Entry entry);

  Optional<Entry> find(UUID id, UUID userId);

  Optional<Entry> lock(UUID id, UUID userId);

  Optional<Entry> forAdministration(UUID id);

  boolean revoke(UUID id, UUID userId, Instant now);

  List<Entry> expired(Instant now);

  boolean recordExpiration(UUID id, Instant now);

  boolean deferObservation(UUID id, int expectedAttempts, Instant failedAt, Instant retryAt);

  Observation operations(Instant now);

  record Observation(
      long pending, long ready, long deferred, long retrying, double oldestOverdueSeconds) {}

  record Entry(
      UUID id,
      UUID userId,
      String target,
      String justification,
      Instant createdAt,
      Instant expiresAt,
      Instant revokedAt,
      String originCorrelationId,
      int observationAttempts) {
    public Entry(
        UUID id,
        UUID userId,
        String target,
        String justification,
        Instant createdAt,
        Instant expiresAt,
        Instant revokedAt,
        String originCorrelationId) {
      this(
          id,
          userId,
          target,
          justification,
          createdAt,
          expiresAt,
          revokedAt,
          originCorrelationId,
          0);
    }

    public Entry(
        UUID id,
        UUID userId,
        String target,
        String justification,
        Instant createdAt,
        Instant expiresAt,
        Instant revokedAt) {
      this(id, userId, target, justification, createdAt, expiresAt, revokedAt, id.toString());
    }

    @Override
    public String toString() {
      return "PrivilegedRequest[redacted]";
    }
  }
}

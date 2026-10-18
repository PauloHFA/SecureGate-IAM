package com.securegate.iam.identity.application.port;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** All mutations run under the user's database row lock and the caller's UnitOfWork. */
public interface MfaStore {
  boolean lockCurrentUser(UUID userId, long version);

  boolean lockOpenSession(UUID sessionId);

  boolean passwordMatches(UUID userId, String password);

  Factor load(UUID userId);

  void save(UUID userId, Factor factor);

  void recovery(UUID factorId, List<String> hashes);

  boolean consumeRecovery(UUID factorId, String hash);

  int remainingRecovery(UUID factorId);

  void proof(UUID userId, long version, UUID sessionId, UUID factorId, String method, Instant now);

  record Factor(
      UUID id,
      String state,
      String ciphertext,
      UUID enrollmentSession,
      Long enrollmentVersion,
      Instant pendingExpiresAt,
      long lastCounter,
      int failures,
      Instant blockedUntil) {
    public Factor attempts(int failures, Instant blockedUntil) {
      return new Factor(
          id,
          state,
          ciphertext,
          enrollmentSession,
          enrollmentVersion,
          pendingExpiresAt,
          lastCounter,
          failures,
          blockedUntil);
    }

    @Override
    public String toString() {
      return "Factor[redacted]";
    }
  }
}

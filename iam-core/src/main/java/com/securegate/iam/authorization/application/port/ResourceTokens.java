package com.securegate.iam.authorization.application.port;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Trusted access-token snapshot, after signature, audience and current grant validation. */
public interface ResourceTokens {
  Optional<Subject> resolve(String token, String audience);

  record Subject(
      UUID userId,
      long version,
      UUID sid,
      String jti,
      Set<String> scopes,
      Instant issuedAt,
      Instant expiresAt) {
    public Subject {
      scopes = Set.copyOf(scopes);
    }
  }
}

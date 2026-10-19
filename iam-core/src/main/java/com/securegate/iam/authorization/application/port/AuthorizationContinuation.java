package com.securegate.iam.authorization.application.port;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Access only using identity and sid from the authenticated server session. */
public interface AuthorizationContinuation {
  void begin(
      UUID userId,
      long version,
      UUID sid,
      String clientName,
      Map<String, String> validatedParameters);

  Optional<View> current(UUID userId, long version, UUID sid);

  Map<String, String> resume(String ticket, UUID userId, long version, UUID sid);

  void clear();

  record View(String ticket, String clientName, Instant expiresAt, boolean ready) {}

  final class Unavailable extends RuntimeException {
    public Unavailable() {
      super("Authorization continuation unavailable.");
    }
  }
}

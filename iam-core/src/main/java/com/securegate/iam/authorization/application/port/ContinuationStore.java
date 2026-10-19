package com.securegate.iam.authorization.application.port;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface ContinuationStore {
  void save(Pending pending);

  Optional<Pending> load();

  boolean remove(String ticket);

  void clear();

  record Pending(
      String ticket,
      UUID userId,
      long version,
      UUID sid,
      String clientName,
      Map<String, String> parameters,
      Instant expiresAt) {
    public Pending {
      parameters = Map.copyOf(parameters);
    }

    @Override
    public String toString() {
      return "PendingAuthorization[redacted]";
    }
  }
}

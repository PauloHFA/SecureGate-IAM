package com.securegate.iam.authorization.domain;

import java.util.Set;

public record MachineClient(String clientId, String name, String audience, Set<String> scopes) {
  public MachineClient {
    if (clientId == null
        || !clientId.matches("[a-z][a-z0-9_-]{2,63}")
        || name == null
        || name.isBlank()
        || name.length() > 120
        || audience == null
        || !audience.matches("[a-zA-Z][a-zA-Z0-9:/._-]{0,199}")
        || scopes == null
        || scopes.isEmpty()
        || scopes.size() > 10
        || scopes.stream()
            .anyMatch(
                scope ->
                    scope == null
                        || !scope.matches("[a-z][a-z0-9:._-]{0,63}")
                        || Set.of("openid", "offline_access").contains(scope))) {
      throw new IllegalArgumentException("Invalid machine client.");
    }
    name = name.strip();
    scopes = Set.copyOf(scopes);
  }
}

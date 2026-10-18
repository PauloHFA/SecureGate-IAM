package com.securegate.iam.authorization.application;

import java.util.Set;

public record ClientView(
    String clientId, String name, String audience, Set<String> scopes, long accessTokenTtlSeconds) {
  public ClientView {
    scopes = Set.copyOf(scopes);
  }
}

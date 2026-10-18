package com.securegate.iam.authorization.application;

import java.util.Set;

public record ClientView(
    String clientId,
    String name,
    String audience,
    Set<String> scopes,
    long accessTokenTtlSeconds,
    boolean refreshTokens,
    boolean oidc) {
  public ClientView(
      String clientId,
      String name,
      String audience,
      Set<String> scopes,
      long accessTokenTtlSeconds) {
    this(clientId, name, audience, scopes, accessTokenTtlSeconds, false, false);
  }

  public ClientView {
    scopes = Set.copyOf(scopes);
  }
}

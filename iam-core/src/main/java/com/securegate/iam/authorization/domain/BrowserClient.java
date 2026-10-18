package com.securegate.iam.authorization.domain;

import java.net.URI;
import java.util.Set;

public record BrowserClient(
    MachineClient definition, Set<String> redirectUris, boolean refreshTokens) {
  public BrowserClient(MachineClient definition, Set<String> redirectUris) {
    this(definition, redirectUris, false);
  }

  public BrowserClient {
    if (definition == null
        || redirectUris == null
        || redirectUris.isEmpty()
        || redirectUris.size() > 3
        || redirectUris.stream().anyMatch(uri -> !validRedirect(uri))) {
      throw new IllegalArgumentException("Invalid browser client redirect URI.");
    }
    redirectUris = Set.copyOf(redirectUris);
  }

  private static boolean validRedirect(String value) {
    if (value == null || value.length() > 250) {
      return false;
    }
    try {
      URI uri = URI.create(value);
      boolean local = "127.0.0.1".equals(uri.getHost()) || "localhost".equals(uri.getHost());
      return uri.getHost() != null
          && uri.getPort() <= 65535
          && uri.getPort() != 0
          && ("https".equals(uri.getScheme()) || (local && "http".equals(uri.getScheme())))
          && uri.getUserInfo() == null
          && uri.getFragment() == null
          && uri.getQuery() == null
          && !uri.getPath().isEmpty();
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }
}

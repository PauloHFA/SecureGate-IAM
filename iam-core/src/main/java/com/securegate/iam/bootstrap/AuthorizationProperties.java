package com.securegate.iam.bootstrap;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("iam.authorization")
public record AuthorizationProperties(
    @DefaultValue("false") boolean enabled, @DefaultValue("http://127.0.0.1:8080") URI issuer) {
  public AuthorizationProperties {
    boolean local =
        issuer != null
            && ("127.0.0.1".equals(issuer.getHost()) || "localhost".equals(issuer.getHost()));
    if (issuer == null
        || issuer.getHost() == null
        || issuer.getPort() > 65535
        || !("https".equals(issuer.getScheme()) || (local && "http".equals(issuer.getScheme())))
        || issuer.getUserInfo() != null
        || issuer.getQuery() != null
        || issuer.getFragment() != null
        || !issuer.getPath().isEmpty()) {
      throw new IllegalArgumentException("Invalid authorization issuer.");
    }
  }
}

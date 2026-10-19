package com.securegate.iam.bootstrap;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("iam.resources.enforcement")
public record ResourceEnforcementProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("127.0.0.1") String host,
    @DefaultValue("6379") int port,
    @DefaultValue("") String password,
    @DefaultValue("500") int timeoutMillis) {
  public ResourceEnforcementProperties {
    if (host == null
        || !host.matches("[a-zA-Z0-9:._-]{1,253}")
        || port < 1
        || port > 65535
        || timeoutMillis < 100
        || timeoutMillis > 2000
        || (enabled && (password == null || password.isBlank()))) {
      throw new IllegalArgumentException("Invalid resource enforcement settings.");
    }
  }

  @Override
  public String toString() {
    return "ResourceEnforcementProperties[credentials=redacted]";
  }
}

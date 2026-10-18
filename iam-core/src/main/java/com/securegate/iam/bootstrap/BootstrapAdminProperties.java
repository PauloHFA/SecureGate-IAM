package com.securegate.iam.bootstrap;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("iam.bootstrap")
public record BootstrapAdminProperties(boolean enabled, String token) {
  public BootstrapAdminProperties {
    if (enabled
        && (token == null
            || token.length() < 32
            || token.length() > 256
            || token.chars().anyMatch(Character::isWhitespace))) {
      throw new IllegalArgumentException(
          "Bootstrap requires a token of 32 to 256 characters without whitespace");
    }
  }

  @Override
  public String toString() {
    return "BootstrapAdminProperties[enabled=" + enabled + ", token=REDACTED]";
  }
}

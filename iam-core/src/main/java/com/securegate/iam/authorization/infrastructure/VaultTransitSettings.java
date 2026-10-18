package com.securegate.iam.authorization.infrastructure;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("iam.transit")
public record VaultTransitSettings(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("http://127.0.0.1:8200") URI address,
    @DefaultValue("infra/.local/iam-core-role-id") Path roleIdFile,
    @DefaultValue("infra/.local/iam-core-secret-id") Path secretIdFile,
    @DefaultValue("securegate-jwt") String keyName,
    @DefaultValue("2s") Duration timeout,
    @DefaultValue("30s") Duration publicKeyCacheTtl,
    @DefaultValue("8") int maxConcurrentRequests) {
  public VaultTransitSettings {
    boolean local =
        address != null
            && ("127.0.0.1".equals(address.getHost()) || "localhost".equals(address.getHost()));
    if (address == null
        || address.getHost() == null
        || address.getPort() > 65535
        || !("https".equals(address.getScheme()) || (local && "http".equals(address.getScheme())))
        || address.getUserInfo() != null
        || address.getQuery() != null
        || address.getFragment() != null
        || !(address.getPath().isEmpty() || "/".equals(address.getPath()))
        || keyName == null
        || !keyName.matches("[a-zA-Z0-9_-]{1,64}")
        || roleIdFile == null
        || secretIdFile == null
        || timeout == null
        || timeout.compareTo(Duration.ofMillis(100)) < 0
        || timeout.compareTo(Duration.ofSeconds(10)) > 0
        || publicKeyCacheTtl == null
        || publicKeyCacheTtl.compareTo(Duration.ofSeconds(1)) < 0
        || publicKeyCacheTtl.compareTo(Duration.ofMinutes(5)) > 0
        || maxConcurrentRequests < 1
        || maxConcurrentRequests > 64) {
      throw new IllegalArgumentException("Invalid Transit configuration.");
    }
  }
}

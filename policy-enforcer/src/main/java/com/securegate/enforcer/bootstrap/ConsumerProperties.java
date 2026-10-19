package com.securegate.enforcer.bootstrap;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("enforcer.consumer")
public record ConsumerProperties(
    @DefaultValue("127.0.0.1") String host,
    @DefaultValue("5672") int port,
    @DefaultValue("securegate") String virtualHost,
    String username,
    String password,
    @DefaultValue("20") int batchSize,
    @DefaultValue("500") int pollMillis) {
  public ConsumerProperties {
    if (host == null
        || !host.matches("[a-zA-Z0-9:._-]{1,253}")
        || port < 1
        || port > 65535
        || virtualHost == null
        || virtualHost.isBlank()
        || virtualHost.length() > 128
        || username == null
        || username.isBlank()
        || password == null
        || password.isBlank()
        || batchSize < 1
        || batchSize > 100
        || pollMillis < 100
        || pollMillis > 60000) {
      throw new IllegalArgumentException("Invalid consumer settings.");
    }
  }

  @Override
  public String toString() {
    return "ConsumerProperties[credentials=redacted]";
  }
}

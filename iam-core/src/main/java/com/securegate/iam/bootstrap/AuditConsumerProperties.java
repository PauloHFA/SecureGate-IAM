package com.securegate.iam.bootstrap;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("iam.audit.consumer")
public record AuditConsumerProperties(
    @DefaultValue("127.0.0.1") String host,
    @DefaultValue("5672") int port,
    @DefaultValue("securegate") String virtualHost,
    String username,
    String password,
    @DefaultValue("20") int batchSize,
    @DefaultValue("1000") int pollMillis) {
  public AuditConsumerProperties {
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
      throw new IllegalArgumentException("Invalid audit consumer settings.");
    }
  }

  @Override
  public String toString() {
    return "AuditConsumerProperties[credentials=redacted]";
  }
}

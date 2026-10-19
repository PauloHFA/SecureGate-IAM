package com.securegate.iam.bootstrap;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("iam.outbox.publisher")
public record OutboxPublisherProperties(
    @DefaultValue("127.0.0.1") String host,
    @DefaultValue("5672") int port,
    @DefaultValue("securegate") String virtualHost,
    String username,
    String password,
    @DefaultValue("2000") int timeoutMillis,
    @DefaultValue("30") int leaseSeconds,
    @DefaultValue("20") int batchSize,
    @DefaultValue("1000") int pollMillis) {
  public OutboxPublisherProperties {
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
        || timeoutMillis < 100
        || timeoutMillis > 5000
        || leaseSeconds < 10
        || leaseSeconds > 300
        || leaseSeconds * 1000 < timeoutMillis * 4
        || batchSize < 1
        || batchSize > 100
        || pollMillis < 100
        || pollMillis > 60000) {
      throw new IllegalArgumentException("Invalid outbox publisher settings.");
    }
  }

  @Override
  public String toString() {
    return "OutboxPublisherProperties[credentials=redacted]";
  }
}

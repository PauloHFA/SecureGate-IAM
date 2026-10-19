package com.securegate.iam.bootstrap;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("iam.outbox.operations")
public record OutboxOperationsProperties(
    @DefaultValue("false") boolean retentionEnabled,
    @DefaultValue("7") int retentionDays,
    @DefaultValue("100") int batchSize,
    @DefaultValue("30000") int pollMillis) {
  public OutboxOperationsProperties {
    if (retentionDays < 1
        || retentionDays > 3650
        || batchSize < 1
        || batchSize > 1000
        || pollMillis < 1000
        || pollMillis > 3600000) {
      throw new IllegalArgumentException("Invalid outbox operations settings.");
    }
  }
}

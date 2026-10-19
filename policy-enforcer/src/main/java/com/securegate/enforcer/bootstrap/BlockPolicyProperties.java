package com.securegate.enforcer.bootstrap;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("enforcer.block-policy")
public record BlockPolicyProperties(
    @DefaultValue("300") int windowSeconds,
    @DefaultValue("300") int blockSeconds,
    @DefaultValue("300") int riskSeconds,
    @DefaultValue("5") int accountThreshold,
    @DefaultValue("20") int ipThreshold) {
  public BlockPolicyProperties {
    if (windowSeconds < 1
        || windowSeconds > 3600
        || blockSeconds < 1
        || blockSeconds > 3600
        || riskSeconds < 1
        || riskSeconds > 3600
        || accountThreshold < 1
        || accountThreshold > 1000
        || ipThreshold < 1
        || ipThreshold > 1000) {
      throw new IllegalArgumentException("Invalid block/policy settings.");
    }
  }
}

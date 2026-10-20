package com.securegate.iam.bootstrap;

import com.securegate.iam.audit.infrastructure.AuditPartitionMaintenance;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "iam.audit.partitions.enabled", havingValue = "true")
@EnableConfigurationProperties(AuditPartitionConfiguration.Settings.class)
public class AuditPartitionConfiguration {
  @ConfigurationProperties("iam.audit.partitions")
  public record Settings(
      @DefaultValue("3") int monthsAhead, @DefaultValue("3600000") int pollMillis) {
    public Settings {
      if (monthsAhead < 1 || monthsAhead > 12 || pollMillis < 1000 || pollMillis > 86400000) {
        throw new IllegalArgumentException("Invalid audit partition settings.");
      }
    }
  }

  @Bean(initMethod = "start", destroyMethod = "close")
  AuditPartitionMaintenance auditPartitions(
      JdbcTemplate jdbc, MeterRegistry meters, Clock clock, Settings settings) {
    return new AuditPartitionMaintenance(
        jdbc, meters, clock, settings.monthsAhead(), settings.pollMillis());
  }
}

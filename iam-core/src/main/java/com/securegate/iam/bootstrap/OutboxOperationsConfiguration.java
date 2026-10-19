package com.securegate.iam.bootstrap;

import com.securegate.iam.audit.infrastructure.JdbcOutboxOperations;
import com.securegate.iam.audit.infrastructure.OutboxMaintenance;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OutboxOperationsProperties.class)
public class OutboxOperationsConfiguration {
  @Bean
  JdbcOutboxOperations outboxOperations(JdbcTemplate jdbc, PlatformTransactionManager manager) {
    return new JdbcOutboxOperations(jdbc, manager);
  }

  @Bean(initMethod = "start", destroyMethod = "close")
  OutboxMaintenance outboxMaintenance(
      JdbcOutboxOperations operations, MeterRegistry meters, OutboxOperationsProperties settings) {
    return new OutboxMaintenance(
        operations,
        meters,
        settings.retentionEnabled(),
        settings.retentionDays(),
        settings.batchSize(),
        settings.pollMillis());
  }
}

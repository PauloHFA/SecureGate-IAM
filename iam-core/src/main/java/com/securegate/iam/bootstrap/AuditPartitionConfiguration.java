package com.securegate.iam.bootstrap;

import com.securegate.iam.audit.infrastructure.AuditDatabaseConnection;
import com.securegate.iam.audit.infrastructure.AuditPartitionMaintenance;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "iam.audit.partitions.enabled", havingValue = "true")
@EnableConfigurationProperties(AuditPartitionConfiguration.Settings.class)
public class AuditPartitionConfiguration {
  @ConfigurationProperties("iam.audit.partitions")
  public record Settings(
      @DefaultValue("3") int monthsAhead,
      @DefaultValue("3600000") int pollMillis,
      String databaseUrl,
      String username,
      String passwordFile) {
    public Settings {
      if (monthsAhead < 1 || monthsAhead > 12 || pollMillis < 1000 || pollMillis > 86400000) {
        throw new IllegalArgumentException("Invalid audit partition settings.");
      }
    }

    @Override
    public String toString() {
      return "AuditPartitionSettings[credentials=redacted]";
    }
  }

  @Bean(destroyMethod = "close")
  @DependsOnDatabaseInitialization
  AuditDatabaseConnection auditMaintenanceConnection(Settings settings) {
    return new AuditDatabaseConnection(
        settings.databaseUrl(),
        settings.username(),
        settings.passwordFile(),
        "securegate_audit_maintenance_login");
  }

  @Bean(initMethod = "start", destroyMethod = "close")
  AuditPartitionMaintenance auditPartitions(
      @Qualifier("auditMaintenanceConnection") AuditDatabaseConnection connection,
      MeterRegistry meters,
      Settings settings) {
    return new AuditPartitionMaintenance(
        connection.jdbc(), meters, settings.monthsAhead(), settings.pollMillis());
  }
}

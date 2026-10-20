package com.securegate.iam.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securegate.iam.audit.application.port.AuditEventStore;
import com.securegate.iam.audit.infrastructure.AuditDatabaseConnection;
import com.securegate.iam.audit.infrastructure.JdbcAuditEventStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "iam.audit.ingestion.enabled", havingValue = "true")
@EnableConfigurationProperties(AuditIngestionConfiguration.Settings.class)
public class AuditIngestionConfiguration {
  @ConfigurationProperties("iam.audit.ingestion")
  public record Settings(String databaseUrl, String username, String passwordFile) {
    @Override
    public String toString() {
      return "AuditIngestionSettings[credentials=redacted]";
    }
  }

  @Bean(destroyMethod = "close")
  @DependsOnDatabaseInitialization
  AuditDatabaseConnection auditIngestionConnection(Settings settings) {
    return new AuditDatabaseConnection(
        settings.databaseUrl(),
        settings.username(),
        settings.passwordFile(),
        "securegate_audit_ingest_login");
  }

  @Bean
  AuditEventStore auditEventStore(
      @Qualifier("auditIngestionConnection") AuditDatabaseConnection connection,
      ObjectMapper json) {
    return new JdbcAuditEventStore(connection.jdbc(), json, connection.transactions());
  }
}

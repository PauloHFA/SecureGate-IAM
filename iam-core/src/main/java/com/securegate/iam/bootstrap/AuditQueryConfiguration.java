package com.securegate.iam.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securegate.iam.audit.application.AuditQueryService;
import com.securegate.iam.audit.application.port.AuditEventQueries;
import com.securegate.iam.audit.infrastructure.AuditDatabaseConnection;
import com.securegate.iam.audit.infrastructure.JdbcAuditEventQueries;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "iam.audit.query.enabled", havingValue = "true")
public class AuditQueryConfiguration {
  @Bean
  AuditEventQueries auditEventQueries(
      @Qualifier("auditIngestionConnection") AuditDatabaseConnection connection,
      ObjectMapper json) {
    return new JdbcAuditEventQueries(connection.jdbc(), json, connection.transactions());
  }

  @Bean
  AuditQueryService auditQueryService(AuditEventQueries queries) {
    return new AuditQueryService(queries);
  }
}

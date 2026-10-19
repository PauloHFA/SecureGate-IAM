package com.securegate.iam.bootstrap;

import com.securegate.iam.audit.application.port.AuditTrail;
import com.securegate.iam.identity.application.MfaService;
import com.securegate.iam.identity.application.port.MfaProofs;
import com.securegate.iam.identity.application.port.MfaSecretProtector;
import com.securegate.iam.identity.infrastructure.JdbcMfaStore;
import com.securegate.iam.shared.application.port.UnitOfWork;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "iam.authorization.enabled", havingValue = "true")
public class MfaConfiguration {
  @Bean
  JdbcMfaStore mfaStore(JdbcTemplate jdbc, Clock clock) {
    return new JdbcMfaStore(jdbc, clock);
  }

  @Bean
  MfaService mfaService(
      JdbcMfaStore store,
      MfaProofs proofs,
      MfaSecretProtector secrets,
      AuditTrail audit,
      UnitOfWork transactions,
      Clock clock) {
    return new MfaService(store, proofs, secrets, audit, transactions, clock);
  }
}

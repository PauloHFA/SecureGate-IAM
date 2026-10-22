package com.securegate.iam.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securegate.iam.access.application.AccessService;
import com.securegate.iam.access.application.port.AccessRepository;
import com.securegate.iam.access.application.port.EffectiveRoles;
import com.securegate.iam.access.infrastructure.JdbcAccessRepository;
import com.securegate.iam.access.infrastructure.JdbcEffectiveRoles;
import com.securegate.iam.audit.application.port.AuditTrail;
import com.securegate.iam.audit.infrastructure.JdbcAuditTrail;
import com.securegate.iam.audit.infrastructure.JdbcOutboxEvents;
import com.securegate.iam.identity.application.UserService;
import com.securegate.iam.identity.application.port.CredentialRepository;
import com.securegate.iam.identity.application.port.IdentityLookup;
import com.securegate.iam.identity.application.port.PasswordHasher;
import com.securegate.iam.identity.application.port.UserRepository;
import com.securegate.iam.identity.infrastructure.Argon2PasswordHasher;
import com.securegate.iam.identity.infrastructure.JdbcCredentialRepository;
import com.securegate.iam.identity.infrastructure.JdbcUserRepository;
import com.securegate.iam.shared.application.port.UnitOfWork;
import com.securegate.iam.shared.infrastructure.JdbcUnitOfWork;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
public class ProvisioningConfiguration {
  @Bean
  EffectiveRoles effectiveRoles(JdbcTemplate jdbc) {
    return new JdbcEffectiveRoles(jdbc);
  }

  @Bean
  UnitOfWork unitOfWork(PlatformTransactionManager manager) {
    return new JdbcUnitOfWork(new TransactionTemplate(manager));
  }

  @Bean
  UserRepository userRepository(JdbcTemplate jdbc) {
    return new JdbcUserRepository(jdbc);
  }

  @Bean
  CredentialRepository credentialRepository(JdbcTemplate jdbc) {
    return new JdbcCredentialRepository(jdbc);
  }

  @Bean
  PasswordHasher passwordHasher() {
    return new Argon2PasswordHasher();
  }

  @Bean
  JdbcOutboxEvents outboxEvents(
      JdbcTemplate jdbc,
      ObjectMapper json,
      @Nullable Tracer tracer,
      @Nullable Propagator propagator) {
    return new JdbcOutboxEvents(
        jdbc,
        json,
        () -> {
          if (tracer == null || propagator == null || tracer.currentSpan() == null) return null;
          var parent = new java.util.HashMap<String, String>();
          propagator.inject(
              tracer.currentSpan().context(),
              parent,
              (target, key, value) -> {
                if ("traceparent".equals(key)) target.put(key, value);
              });
          return parent.get("traceparent");
        });
  }

  @Bean
  AuditTrail auditTrail(
      JdbcTemplate jdbc,
      ObjectMapper json,
      JdbcOutboxEvents outbox,
      PlatformTransactionManager manager,
      Clock clock) {
    return new JdbcAuditTrail(jdbc, json, outbox, new TransactionTemplate(manager), clock);
  }

  @Bean
  UserService userService(
      UserRepository users,
      CredentialRepository credentials,
      PasswordHasher hasher,
      AuditTrail audit,
      UnitOfWork transactions) {
    return new UserService(users, credentials, hasher, audit, transactions);
  }

  @Bean
  AccessRepository accessRepository(JdbcTemplate jdbc) {
    return new JdbcAccessRepository(jdbc);
  }

  @Bean
  AccessService accessService(
      AccessRepository access,
      IdentityLookup identities,
      AuditTrail audit,
      UnitOfWork transactions) {
    return new AccessService(access, identities, audit, transactions);
  }
}

package com.securegate.iam.bootstrap;

import com.securegate.iam.audit.application.port.AuditTrail;
import com.securegate.iam.authorization.application.PamCredentialService;
import com.securegate.iam.authorization.application.PrivilegedRequestService;
import com.securegate.iam.authorization.application.ResourceAccessService;
import com.securegate.iam.authorization.infrastructure.*;
import com.securegate.iam.shared.application.port.UnitOfWork;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "iam.pam.credentials.enabled", havingValue = "true")
public class PamCredentialConfiguration {
  @Bean
  PamCredentialService pamCredentialService(
      JdbcTemplate jdbc,
      UnitOfWork transactions,
      AuditTrail audit,
      Clock clock,
      ResourceAccessService access,
      PrivilegedRequestService requests,
      @Value("${iam.pam.credentials.address}") URI address,
      @Value("${iam.pam.credentials.role-id-file}") Path role,
      @Value("${iam.pam.credentials.secret-id-file}") Path secret) {
    return new PamCredentialService(
        new JdbcPrivilegedRequests(jdbc),
        new JdbcPrivilegedIssuances(jdbc),
        new VaultPamCredentials(address, role, secret),
        access,
        transactions,
        audit,
        clock);
  }

  @Bean(destroyMethod = "shutdown")
  ScheduledExecutorService pamMaintenance(PamCredentialService service, MeterRegistry metrics) {
    var worker =
        Executors.newSingleThreadScheduledExecutor(
            task -> {
              var thread = new Thread(task, "pam-lease-maintenance");
              thread.setDaemon(true);
              return thread;
            });
    worker.scheduleWithFixedDelay(
        () -> {
          try {
            service.maintain();
          } catch (RuntimeException failure) {
            metrics.counter("iam.pam.cleanup.failures").increment();
          }
        },
        500,
        500,
        TimeUnit.MILLISECONDS);
    return worker;
  }
}

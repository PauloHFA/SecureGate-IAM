package com.securegate.iam.bootstrap;

import com.securegate.iam.audit.application.port.AuditTrail;
import com.securegate.iam.authorization.application.PrivilegedRequestService;
import com.securegate.iam.authorization.application.ResourceAccessService;
import com.securegate.iam.authorization.infrastructure.JdbcPrivilegedRequests;
import com.securegate.iam.shared.application.port.UnitOfWork;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.concurrent.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "iam.pam.enabled", havingValue = "true")
public class PrivilegedRequestConfiguration {
  @Bean
  PrivilegedRequestService privilegedRequestService(
      JdbcTemplate jdbc,
      UnitOfWork transactions,
      AuditTrail audit,
      Clock clock,
      ResourceAccessService access) {
    return new PrivilegedRequestService(
        new JdbcPrivilegedRequests(jdbc), transactions, audit, clock);
  }

  @Bean(destroyMethod = "shutdown")
  ScheduledExecutorService pamRequestExpirationAudit(
      PrivilegedRequestService service, MeterRegistry metrics) {
    var worker =
        Executors.newSingleThreadScheduledExecutor(
            task -> {
              var thread = new Thread(task, "pam-request-expiration");
              thread.setDaemon(true);
              return thread;
            });
    worker.scheduleWithFixedDelay(
        () -> {
          try {
            service.observeExpirations();
          } catch (RuntimeException failure) {
            metrics.counter("iam.pam.expiration.audit.failures").increment();
          }
        },
        500,
        500,
        TimeUnit.MILLISECONDS);
    return worker;
  }
}

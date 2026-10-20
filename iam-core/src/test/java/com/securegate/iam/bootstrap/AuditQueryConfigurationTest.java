package com.securegate.iam.bootstrap;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AuditQueryConfigurationTest {
  private final ApplicationContextRunner context =
      new ApplicationContextRunner()
          .withUserConfiguration(AuditQueryConfiguration.class)
          .withBean(ObjectMapper.class, () -> new ObjectMapper().findAndRegisterModules());

  @Test
  void disabledByDefaultAndEnabledModeCannotFallBackToMainDataSource() {
    context.run(
        application -> {
          assertNull(application.getStartupFailure());
          assertFalse(application.containsBean("auditQueryService"));
          assertFalse(application.containsBean("auditEventQueries"));
        });
    context
        .withPropertyValues("iam.audit.query.enabled=true")
        .run(application -> assertNotNull(application.getStartupFailure()));
  }
}

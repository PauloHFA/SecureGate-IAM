package com.securegate.iam.bootstrap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securegate.iam.audit.application.port.AuditEventStore;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AuditConsumerConfigurationTest {
  private final ApplicationContextRunner context =
      new ApplicationContextRunner()
          .withUserConfiguration(AuditConsumerConfiguration.class)
          .withBean(ObjectMapper.class, () -> new ObjectMapper().findAndRegisterModules())
          .withBean(MeterRegistry.class, SimpleMeterRegistry::new);

  @Test
  void disabledByDefaultAndCannotStartWithoutDedicatedStore() {
    context.run(application -> assertFalse(application.containsBean("auditConsumer")));
    context
        .withPropertyValues(
            "iam.audit.consumer.enabled=true",
            "iam.audit.consumer.username=test",
            "iam.audit.consumer.password=synthetic")
        .run(application -> assertNotNull(application.getStartupFailure()));
  }

  @Test
  void createsOptionalConsumerAndValidatesBoundsWithoutExposingCredentials() {
    context
        .withBean(AuditEventStore.class, () -> mock(AuditEventStore.class))
        .withPropertyValues(
            "iam.audit.consumer.enabled=true",
            "iam.audit.consumer.username=test",
            "iam.audit.consumer.password=synthetic",
            "iam.audit.consumer.port=1")
        .run(
            application -> {
              assertNull(application.getStartupFailure());
              assertTrue(application.containsBean("auditConsumer"));
              assertFalse(
                  application
                      .getBean(AuditConsumerProperties.class)
                      .toString()
                      .contains("synthetic"));
            });
    for (String invalid :
        new String[] {
          "iam.audit.consumer.batch-size=101",
          "iam.audit.consumer.poll-millis=99",
          "iam.audit.consumer.port=0"
        }) {
      context
          .withBean(AuditEventStore.class, () -> mock(AuditEventStore.class))
          .withPropertyValues(
              "iam.audit.consumer.enabled=true",
              "iam.audit.consumer.username=test",
              "iam.audit.consumer.password=synthetic",
              invalid)
          .run(application -> assertNotNull(application.getStartupFailure()));
    }
  }
}

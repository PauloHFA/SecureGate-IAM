package com.securegate.iam.bootstrap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.securegate.iam.audit.infrastructure.AuditPartitionMaintenance;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

class AuditPartitionConfigurationTest {
  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final ApplicationContextRunner context =
      new ApplicationContextRunner()
          .withUserConfiguration(
              AuditPartitionConfiguration.class, AuditIngestionConfiguration.class)
          .withBean(JdbcTemplate.class, () -> jdbc)
          .withBean(MeterRegistry.class, () -> meters);

  @Test
  void disabledModeDoesNotCreatePoolsAndEnabledModeCannotFallBackToTheOwner() {
    context.run(
        application -> {
          assertFalse(application.containsBean("auditPartitions"));
          assertFalse(application.containsBean("auditEventStore"));
        });
    context
        .withPropertyValues("iam.audit.partitions.enabled=true")
        .run(application -> assertNotNull(application.getStartupFailure()));
    context
        .withPropertyValues("iam.audit.ingestion.enabled=true")
        .run(application -> assertNotNull(application.getStartupFailure()));
    verify(jdbc, never()).queryForObject(anyString(), eq(Integer.class), anyInt());
  }

  @Test
  void failedMaintenanceDoesNotDisableTheNextTickOrQueryDuringScrape() {
    when(jdbc.queryForObject("SELECT iam_audit.maintain_event_partitions(?)", Integer.class, 3))
        .thenThrow(new IllegalStateException("synthetic unavailable database"))
        .thenReturn(2);
    try (var worker = new AuditPartitionMaintenance(jdbc, meters, 3, 1000)) {
      worker.runOnce();
      assertEquals(0.0, meters.get("iam.audit.partitions.available").gauge().value());
      worker.runOnce();
      assertEquals(1.0, meters.get("iam.audit.partitions.available").gauge().value());
      assertEquals(2.0, meters.get("iam.audit.partitions.created").counter().count());
      assertEquals(
          1.0,
          meters.get("iam.audit.partitions.runs").tag("result", "unavailable").counter().count());
      verify(jdbc, times(2))
          .queryForObject("SELECT iam_audit.maintain_event_partitions(?)", Integer.class, 3);
      verifyNoMoreInteractions(jdbc);
    }
  }

  @Test
  void configurationRejectsUnboundedHorizonsAndPollRates() {
    context
        .withPropertyValues(
            "iam.audit.partitions.enabled=true", "iam.audit.partitions.months-ahead=25")
        .run(application -> assertNotNull(application.getStartupFailure()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new AuditPartitionConfiguration.Settings(3, 0, null, null, null));
    verify(jdbc, never()).queryForObject(anyString(), eq(Integer.class), anyInt());
  }
}

package com.securegate.iam.bootstrap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.securegate.iam.audit.infrastructure.AuditPartitionMaintenance;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Date;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

class AuditPartitionConfigurationTest {
  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final Clock clock = Clock.fixed(Instant.parse("2030-07-31T23:59:59Z"), ZoneOffset.UTC);
  private final ApplicationContextRunner context =
      new ApplicationContextRunner()
          .withUserConfiguration(AuditPartitionConfiguration.class)
          .withBean(JdbcTemplate.class, () -> jdbc)
          .withBean(MeterRegistry.class, () -> meters)
          .withBean(Clock.class, () -> clock);

  @Test
  void disabledModeDoesNotStartDdlAndEnabledModeSchedulesTheUtcHorizon() {
    context.run(
        application -> {
          assertFalse(application.containsBean("auditPartitions"));
          verify(jdbc, never())
              .queryForObject(anyString(), eq(Integer.class), any(Date.class), anyInt());
        });
    when(jdbc.queryForObject(
            "SELECT iam_audit.ensure_event_partitions(?,?)",
            Integer.class,
            Date.valueOf("2030-06-01"),
            5))
        .thenReturn(0);
    context
        .withPropertyValues("iam.audit.partitions.enabled=true")
        .run(
            application -> {
              assertNull(application.getStartupFailure());
              assertNotNull(application.getBean(AuditPartitionMaintenance.class));
              verify(jdbc, timeout(2000))
                  .queryForObject(
                      "SELECT iam_audit.ensure_event_partitions(?,?)",
                      Integer.class,
                      Date.valueOf("2030-06-01"),
                      5);
            });
  }

  @Test
  void failedMaintenanceDoesNotDisableTheNextTickOrQueryDuringScrape() {
    when(jdbc.queryForObject(
            "SELECT iam_audit.ensure_event_partitions(?,?)",
            Integer.class,
            Date.valueOf("2030-06-01"),
            5))
        .thenThrow(new IllegalStateException("synthetic unavailable database"))
        .thenReturn(2);
    try (var worker = new AuditPartitionMaintenance(jdbc, meters, clock, 3, 1000)) {
      worker.runOnce();
      assertEquals(0.0, meters.get("iam.audit.partitions.available").gauge().value());
      worker.runOnce();
      assertEquals(1.0, meters.get("iam.audit.partitions.available").gauge().value());
      assertEquals(2.0, meters.get("iam.audit.partitions.created").counter().count());
      assertEquals(
          1.0,
          meters.get("iam.audit.partitions.runs").tag("result", "unavailable").counter().count());
      verify(jdbc, times(2))
          .queryForObject(
              "SELECT iam_audit.ensure_event_partitions(?,?)",
              Integer.class,
              Date.valueOf("2030-06-01"),
              5);
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
        IllegalArgumentException.class, () -> new AuditPartitionConfiguration.Settings(3, 0));
    verify(jdbc, never()).queryForObject(anyString(), eq(Integer.class), any(Date.class), anyInt());
  }
}

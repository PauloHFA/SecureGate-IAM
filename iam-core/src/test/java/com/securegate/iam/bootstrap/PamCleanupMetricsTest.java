package com.securegate.iam.bootstrap;

import static org.junit.jupiter.api.Assertions.*;

import com.securegate.iam.authorization.application.port.PrivilegedIssuances.Operations;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.*;
import org.junit.jupiter.api.Test;

class PamCleanupMetricsTest {
  @Test
  void unavailableSnapshotIsNeverReportedAsAnEmptyQueueAndCanRecover() {
    var registry = new SimpleMeterRegistry();
    try {
      var now = Instant.parse("2026-10-20T19:00:00Z");
      var metrics = new PamCleanupMetrics(registry, Clock.fixed(now, ZoneOffset.UTC));
      assertTrue(Double.isNaN(value(registry, "pending")));
      assertEquals(0, value(registry, "snapshot.available"));
      metrics.refresh(() -> new Operations(12, 2, 10, 10, 1, 90));
      assertEquals(12, value(registry, "pending"));
      assertEquals(10, value(registry, "deferred"));
      assertEquals(1, value(registry, "ambiguous"));
      assertEquals(1, value(registry, "snapshot.available"));
      assertEquals(now.getEpochSecond(), value(registry, "snapshot.last.success"));
      assertThrows(
          IllegalStateException.class,
          () ->
              metrics.refresh(
                  () -> {
                    throw new IllegalStateException("synthetic unavailable database");
                  }));
      assertTrue(Double.isNaN(value(registry, "pending")));
      assertEquals(0, value(registry, "snapshot.available"));
      assertEquals(now.getEpochSecond(), value(registry, "snapshot.last.success"));
      metrics.refresh(() -> new Operations(0, 0, 0, 0, 1, 0));
      assertEquals(0, value(registry, "pending"));
      assertEquals(1, value(registry, "snapshot.available"));
      assertTrue(
          registry.getMeters().stream().allMatch(meter -> meter.getId().getTags().isEmpty()));
    } finally {
      registry.close();
    }
  }

  private double value(SimpleMeterRegistry registry, String suffix) {
    return registry.get("iam.pam.cleanup." + suffix).gauge().value();
  }
}

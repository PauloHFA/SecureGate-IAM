package com.securegate.iam.bootstrap;

import static org.junit.jupiter.api.Assertions.*;

import com.securegate.iam.authorization.application.port.PrivilegedRequests.Observation;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.*;
import org.junit.jupiter.api.Test;

class PamRequestObservationMetricsTest {
  @Test
  void failedSnapshotIsUnknownInsteadOfZeroAndRecovers() {
    var registry = new SimpleMeterRegistry();
    try {
      var now = Instant.parse("2026-10-20T20:00:00Z");
      var metrics = new PamRequestObservationMetrics(registry, Clock.fixed(now, ZoneOffset.UTC));
      assertTrue(Double.isNaN(value(registry, "pending")));
      metrics.refresh(() -> new Observation(21, 1, 20, 20, 60));
      assertEquals(21, value(registry, "pending"));
      assertEquals(20, value(registry, "deferred"));
      assertEquals(60, value(registry, "oldest.overdue.seconds"));
      assertThrows(
          IllegalStateException.class,
          () ->
              metrics.refresh(
                  () -> {
                    throw new IllegalStateException();
                  }));
      assertEquals(0, value(registry, "snapshot.available"));
      assertTrue(Double.isNaN(value(registry, "pending")));
      assertEquals(now.getEpochSecond(), value(registry, "snapshot.last.success"));
      metrics.refresh(() -> new Observation(0, 0, 0, 0, 0));
      assertEquals(1, value(registry, "snapshot.available"));
      assertEquals(0, value(registry, "pending"));
      assertTrue(
          registry.getMeters().stream().allMatch(meter -> meter.getId().getTags().isEmpty()));
    } finally {
      registry.close();
    }
  }

  private double value(SimpleMeterRegistry registry, String suffix) {
    return registry.get("iam.pam.expiration." + suffix).gauge().value();
  }
}

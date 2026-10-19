package com.securegate.iam.audit.infrastructure;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.securegate.iam.audit.application.port.EventTransport;
import com.securegate.iam.audit.application.port.OutboxDelivery;
import com.securegate.iam.bootstrap.OutboxOperationsProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class OutboxOperationsTest {
  @Test
  void failedSnapshotIsMarkedStaleAndLaterTickRecoversWithoutLoggingSensitiveMessage(
      CapturedOutput output) {
    var operations = mock(JdbcOutboxOperations.class);
    when(operations.snapshot())
        .thenReturn(Map.of("pending", 3L))
        .thenThrow(new IllegalStateException("synthetic secret must not be logged"))
        .thenReturn(Map.of("pending", 0L));
    var meters = new SimpleMeterRegistry();
    try (var worker = new OutboxMaintenance(operations, meters, false, 7, 100, 1000)) {
      assertTrue(Double.isNaN(meters.get("iam.outbox.pending").gauge().value()));
      worker.runOnce();
      double timestamp = meters.get("iam.outbox.snapshot.timestamp").gauge().value();
      worker.runOnce();
      assertEquals(3.0, meters.get("iam.outbox.pending").gauge().value());
      assertEquals(0.0, meters.get("iam.outbox.snapshot.available").gauge().value());
      assertEquals(timestamp, meters.get("iam.outbox.snapshot.timestamp").gauge().value());
      worker.runOnce();
      assertEquals(0.0, meters.get("iam.outbox.pending").gauge().value());
      assertEquals(1.0, meters.get("iam.outbox.snapshot.available").gauge().value());
      assertEquals(
          1.0,
          meters
              .get("iam.outbox.maintenance.errors")
              .tag("operation", "snapshot")
              .counter()
              .count());
      verify(operations, never()).purgePublished(anyInt(), anyInt());
      assertFalse(output.getAll().contains("synthetic secret must not be logged"));
    }
  }

  @Test
  void retentionFailureDoesNotPreventSnapshotOrLaterCleanup() {
    var operations = mock(JdbcOutboxOperations.class);
    when(operations.purgePublished(7, 10)).thenThrow(new IllegalStateException()).thenReturn(2);
    when(operations.snapshot()).thenReturn(Map.of("pending", 1L));
    var meters = new SimpleMeterRegistry();
    try (var worker = new OutboxMaintenance(operations, meters, true, 7, 10, 1000)) {
      worker.runOnce();
      worker.runOnce();
      assertEquals(2.0, meters.get("iam.outbox.purged").counter().count());
      assertEquals(
          1.0,
          meters
              .get("iam.outbox.maintenance.errors")
              .tag("operation", "retention")
              .counter()
              .count());
      assertEquals(1.0, meters.get("iam.outbox.snapshot.available").gauge().value());
    }
  }

  @Test
  void deliveryCountersDistinguishConfirmedMarksLostLeasesRetriesAndDatabaseErrors() {
    var delegate = mock(OutboxDelivery.class);
    var meters = new SimpleMeterRegistry();
    var delivery = new MeteredOutboxDelivery(delegate, meters);
    when(delegate.claim(30)).thenReturn(Optional.empty()).thenThrow(new IllegalStateException());
    assertTrue(delivery.claim(30).isEmpty());
    assertThrows(IllegalStateException.class, () -> delivery.claim(30));
    when(delegate.published(null)).thenReturn(true, false).thenThrow(new IllegalStateException());
    assertTrue(delivery.published(null));
    assertFalse(delivery.published(null));
    assertThrows(IllegalStateException.class, () -> delivery.published(null));
    when(delegate.retry(null, EventTransport.Outcome.NACK, 2))
        .thenReturn(true, false)
        .thenThrow(new IllegalStateException());
    assertTrue(delivery.retry(null, EventTransport.Outcome.NACK, 2));
    assertFalse(delivery.retry(null, EventTransport.Outcome.NACK, 2));
    assertThrows(
        IllegalStateException.class, () -> delivery.retry(null, EventTransport.Outcome.NACK, 2));
    assertEquals(1, meters.get("iam.outbox.delivery").tag("result", "published").counter().count());
    assertEquals(1, meters.get("iam.outbox.delivery").tag("result", "NACK").counter().count());
    assertEquals(
        2, meters.get("iam.outbox.delivery").tag("result", "lease_lost").counter().count());
    for (String operation : new String[] {"claim", "published", "retry"}) {
      assertEquals(
          1, meters.get("iam.outbox.store.errors").tag("operation", operation).counter().count());
    }
  }

  @Test
  void operationalSettingsRejectUnboundedWork() {
    assertDoesNotThrow(() -> new OutboxOperationsProperties(false, 7, 100, 30000));
    assertThrows(
        IllegalArgumentException.class, () -> new OutboxOperationsProperties(true, 0, 100, 30000));
    assertThrows(
        IllegalArgumentException.class,
        () -> new OutboxOperationsProperties(true, 3651, 100, 30000));
    assertThrows(
        IllegalArgumentException.class, () -> new OutboxOperationsProperties(true, 7, 0, 30000));
    assertThrows(
        IllegalArgumentException.class, () -> new OutboxOperationsProperties(true, 7, 1001, 30000));
    assertThrows(
        IllegalArgumentException.class, () -> new OutboxOperationsProperties(true, 7, 100, 999));
    assertThrows(
        IllegalArgumentException.class,
        () -> new OutboxOperationsProperties(true, 7, 100, 3600001));
  }
}

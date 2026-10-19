package com.securegate.iam.audit.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.securegate.iam.audit.application.port.EventTransport;
import com.securegate.iam.audit.application.port.OutboxDelivery;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class OutboxPublisherTest {
  private final OutboxDelivery store = mock(OutboxDelivery.class);
  private final EventTransport transport = mock(EventTransport.class);
  private final OutboxPublisher publisher = new OutboxPublisher(store, transport, 30);

  private OutboxDelivery.Claim claim(int attempts) {
    return new OutboxDelivery.Claim(
        UUID.randomUUID(), "test.event", 1, "private-payload", attempts, UUID.randomUUID());
  }

  private OutboxDelivery.Claim pending(int attempts) {
    var claim = claim(attempts);
    when(store.claim(30)).thenReturn(Optional.of(claim), Optional.empty());
    return claim;
  }

  @Test
  void onlyBrokerAckAndCurrentLeaseCanMarkPublication() {
    var claim = pending(1);
    when(transport.publish(claim)).thenReturn(EventTransport.Outcome.ACKED);
    when(store.published(claim)).thenReturn(true);
    assertEquals(1, publisher.publishBatch(10));
    verify(store).published(claim);
    verify(store, never()).retry(any(), any(), anyInt());
    assertFalse(claim.toString().contains("private-payload"));
  }

  @ParameterizedTest
  @EnumSource(value = EventTransport.Outcome.class, names = "ACKED", mode = EnumSource.Mode.EXCLUDE)
  void failuresRetainTheSameClaimAndScheduleRetry(EventTransport.Outcome failure) {
    var claim = pending(1);
    when(transport.publish(claim)).thenReturn(failure);
    assertEquals(0, publisher.publishBatch(2));
    verify(store).retry(claim, failure, 2);
    verify(store, never()).published(any());
  }

  @Test
  void lostLeaseIsNotReportedAsPublished() {
    var claim = pending(1);
    when(transport.publish(claim)).thenReturn(EventTransport.Outcome.ACKED);
    when(store.published(claim)).thenReturn(false);
    assertEquals(0, publisher.publishBatch(2));
    verify(store, never()).retry(any(), any(), anyInt());
  }

  @Test
  void brokerExceptionsAndMissingOutcomesAreRetriedWithCappedBackoff() {
    var claim = pending(1000000);
    when(transport.publish(claim)).thenThrow(new IllegalStateException("synthetic"));
    publisher.publishBatch(1);
    verify(store).retry(claim, EventTransport.Outcome.BROKER_UNAVAILABLE, 300);
    reset(store, transport);
    claim = pending(0);
    publisher.publishBatch(1);
    verify(store).retry(claim, EventTransport.Outcome.BROKER_UNAVAILABLE, 2);
  }

  @Test
  void emptyBacklogAndInterruptDoNotContactBroker() {
    when(store.claim(30)).thenReturn(Optional.empty());
    assertEquals(0, publisher.publishBatch(1));
    verifyNoInteractions(transport);
    clearInvocations(store);
    Thread.currentThread().interrupt();
    try {
      assertEquals(0, publisher.publishBatch(1));
      verifyNoInteractions(store);
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void failedDatabaseAcknowledgementPropagatesForLeaseRecovery() {
    var claim = pending(1);
    when(transport.publish(claim)).thenReturn(EventTransport.Outcome.ACKED);
    when(store.published(claim)).thenThrow(new IllegalStateException("synthetic database failure"));
    assertThrows(IllegalStateException.class, () -> publisher.publishBatch(1));
    verify(store, never()).retry(any(), any(), anyInt());
  }

  @Test
  void rejectsInvalidDispatchBounds() {
    for (int lease : new int[] {9, 301}) {
      assertThrows(
          IllegalArgumentException.class, () -> new OutboxPublisher(store, transport, lease));
    }
    for (int limit : new int[] {0, 101}) {
      assertThrows(IllegalArgumentException.class, () -> publisher.publishBatch(limit));
    }
  }
}

package com.securegate.iam.audit.application;

import com.securegate.iam.audit.application.port.EventTransport;
import com.securegate.iam.audit.application.port.OutboxDelivery;

/** At-least-once dispatcher. Retry always uses the persisted event identity and payload. */
public final class OutboxPublisher {
  private final OutboxDelivery outbox;
  private final EventTransport transport;
  private final int leaseSeconds;

  public OutboxPublisher(OutboxDelivery outbox, EventTransport transport, int leaseSeconds) {
    if (leaseSeconds < 10 || leaseSeconds > 300) {
      throw new IllegalArgumentException("Invalid publication lease.");
    }
    this.outbox = outbox;
    this.transport = transport;
    this.leaseSeconds = leaseSeconds;
  }

  public int publishBatch(int limit) {
    if (limit < 1 || limit > 100) {
      throw new IllegalArgumentException("Invalid batch size.");
    }
    int published = 0;
    for (int i = 0; i < limit && !Thread.currentThread().isInterrupted(); i++) {
      var next = outbox.claim(leaseSeconds);
      if (next.isEmpty()) {
        break;
      }
      var claim = next.get();
      EventTransport.Outcome outcome;
      try {
        outcome = transport.publish(claim);
        if (outcome == null) {
          outcome = EventTransport.Outcome.BROKER_UNAVAILABLE;
        }
      } catch (RuntimeException unavailable) {
        outcome = EventTransport.Outcome.BROKER_UNAVAILABLE;
      }
      if (outcome == EventTransport.Outcome.ACKED) {
        if (outbox.published(claim)) {
          published++;
        }
      } else {
        int backoff = 1 << Math.min(Math.max(claim.attempts(), 1), 9);
        outbox.retry(claim, outcome, Math.min(backoff, 300));
      }
    }
    return published;
  }
}

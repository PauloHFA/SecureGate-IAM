package com.securegate.iam.audit.infrastructure;

import com.securegate.iam.audit.application.port.EventTransport;
import com.securegate.iam.audit.application.port.OutboxDelivery;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import java.util.function.Supplier;

/** Fixed-cardinality counters; successful delivery means the fenced database mark succeeded. */
public final class MeteredOutboxDelivery implements OutboxDelivery {
  private final OutboxDelivery delegate;
  private final MeterRegistry meters;

  public MeteredOutboxDelivery(OutboxDelivery delegate, MeterRegistry meters) {
    this.delegate = delegate;
    this.meters = meters;
  }

  @Override
  public Optional<Claim> claim(int leaseSeconds) {
    return measured("claim", () -> delegate.claim(leaseSeconds));
  }

  @Override
  public boolean published(Claim claim) {
    boolean marked = measured("published", () -> delegate.published(claim));
    delivery(marked ? "published" : "lease_lost");
    return marked;
  }

  @Override
  public boolean retry(Claim claim, EventTransport.Outcome failure, int delaySeconds) {
    boolean marked = measured("retry", () -> delegate.retry(claim, failure, delaySeconds));
    delivery(marked ? failure.name() : "lease_lost");
    return marked;
  }

  private void delivery(String result) {
    meters.counter("iam.outbox.delivery", "result", result).increment();
  }

  private <T> T measured(String operation, Supplier<T> action) {
    try {
      return action.get();
    } catch (RuntimeException failure) {
      meters.counter("iam.outbox.store.errors", "operation", operation).increment();
      throw failure;
    }
  }
}

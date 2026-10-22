package com.securegate.iam.bootstrap;

import com.securegate.iam.authorization.application.port.PrivilegedRequests.Observation;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;

/** Gauges read a bounded periodic snapshot, never the database on an HTTP scrape. */
final class PamRequestObservationMetrics {
  private final AtomicReference<Observation> snapshot = new AtomicReference<>();
  private final Clock clock;
  private volatile double lastSuccess;

  PamRequestObservationMetrics(MeterRegistry metrics, Clock clock) {
    this.clock = clock;
    gauge(metrics, "pending", Observation::pending);
    gauge(metrics, "ready", Observation::ready);
    gauge(metrics, "deferred", Observation::deferred);
    gauge(metrics, "retrying", Observation::retrying);
    gauge(metrics, "oldest.overdue.seconds", Observation::oldestOverdueSeconds);
    metrics.gauge(
        "iam.pam.expiration.snapshot.available", this, value -> snapshot.get() == null ? 0 : 1);
    metrics.gauge("iam.pam.expiration.snapshot.last.success", this, value -> lastSuccess);
  }

  private void gauge(MeterRegistry metrics, String suffix, ToDoubleFunction<Observation> read) {
    metrics.gauge(
        "iam.pam.expiration." + suffix,
        this,
        value -> {
          var current = snapshot.get();
          return current == null ? Double.NaN : read.applyAsDouble(current);
        });
  }

  void refresh(Supplier<Observation> query) {
    try {
      snapshot.set(query.get());
      lastSuccess = clock.instant().getEpochSecond();
    } catch (RuntimeException failure) {
      snapshot.set(null);
      throw failure;
    }
  }
}

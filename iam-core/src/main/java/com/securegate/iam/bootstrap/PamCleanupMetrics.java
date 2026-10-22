package com.securegate.iam.bootstrap;

import com.securegate.iam.authorization.application.port.PrivilegedIssuances.Operations;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;

/** Gauges read a bounded periodic snapshot, never the database on an HTTP scrape. */
final class PamCleanupMetrics {
  private final AtomicReference<Operations> snapshot = new AtomicReference<>();
  private final Clock clock;
  private volatile double lastSuccess;

  PamCleanupMetrics(MeterRegistry metrics, Clock clock) {
    this.clock = clock;
    gauge(metrics, "pending", Operations::pending);
    gauge(metrics, "ready", Operations::ready);
    gauge(metrics, "deferred", Operations::deferred);
    gauge(metrics, "retrying", Operations::retrying);
    gauge(metrics, "ambiguous", Operations::ambiguous);
    gauge(metrics, "oldest.pending.age.seconds", Operations::oldestPendingSeconds);
    metrics.gauge(
        "iam.pam.cleanup.snapshot.available", this, value -> snapshot.get() == null ? 0 : 1);
    metrics.gauge("iam.pam.cleanup.snapshot.last.success", this, value -> lastSuccess);
  }

  private void gauge(MeterRegistry metrics, String suffix, ToDoubleFunction<Operations> read) {
    metrics.gauge(
        "iam.pam.cleanup." + suffix,
        this,
        value -> {
          var current = snapshot.get();
          return current == null ? Double.NaN : read.applyAsDouble(current);
        });
  }

  void refresh(Supplier<Operations> query) {
    try {
      snapshot.set(query.get());
      lastSuccess = clock.instant().getEpochSecond();
    } catch (RuntimeException failure) {
      snapshot.set(null);
      throw failure;
    }
  }
}

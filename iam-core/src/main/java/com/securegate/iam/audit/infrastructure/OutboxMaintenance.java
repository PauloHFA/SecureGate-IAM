package com.securegate.iam.audit.infrastructure;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.LoggerFactory;

/** One bounded purge and one cached snapshot per tick; scrape never queries the database. */
public final class OutboxMaintenance implements AutoCloseable {
  private final JdbcOutboxOperations operations;
  private final MeterRegistry meters;
  private final boolean retentionEnabled;
  private final int retentionDays;
  private final int batchSize;
  private final int pollMillis;
  private volatile Map<String, Object> snapshot = Map.of();
  private volatile double lastSuccess;
  private volatile boolean available;
  private final ScheduledExecutorService executor =
      Executors.newSingleThreadScheduledExecutor(
          task -> {
            var thread = new Thread(task, "outbox-maintenance");
            thread.setDaemon(true);
            return thread;
          });

  public OutboxMaintenance(
      JdbcOutboxOperations operations,
      MeterRegistry meters,
      boolean retentionEnabled,
      int retentionDays,
      int batchSize,
      int pollMillis) {
    this.operations = operations;
    this.meters = meters;
    this.retentionEnabled = retentionEnabled;
    this.retentionDays = retentionDays;
    this.batchSize = batchSize;
    this.pollMillis = pollMillis;
    for (String name :
        List.of(
            "pending",
            "published",
            "quarantined",
            "due",
            "leased",
            "expired",
            "failed",
            "oldest_seconds")) {
      meters.gauge(
          "iam.outbox." + name,
          this,
          worker -> {
            Object value = worker.snapshot.get(name);
            return value instanceof Number number ? number.doubleValue() : Double.NaN;
          });
    }
    meters.gauge("iam.outbox.snapshot.available", this, worker -> worker.available ? 1 : 0);
    meters.gauge("iam.outbox.snapshot.timestamp", this, worker -> worker.lastSuccess);
  }

  public void start() {
    executor.scheduleWithFixedDelay(this::runOnce, 0, pollMillis, TimeUnit.MILLISECONDS);
  }

  public void runOnce() {
    if (retentionEnabled) {
      try {
        int removed = operations.purgePublished(retentionDays, batchSize);
        meters.counter("iam.outbox.purged").increment(removed);
      } catch (RuntimeException failure) {
        failed("retention", failure);
      }
    }
    try {
      snapshot = Map.copyOf(operations.snapshot());
      lastSuccess = System.currentTimeMillis() / 1000.0;
      available = true;
    } catch (RuntimeException failure) {
      available = false;
      failed("snapshot", failure);
    }
  }

  private void failed(String operation, RuntimeException failure) {
    meters.counter("iam.outbox.maintenance.errors", "operation", operation).increment();
    LoggerFactory.getLogger(OutboxMaintenance.class)
        .warn(
            "Outbox maintenance unavailable (operation={}, type={})",
            operation,
            failure.getClass().getSimpleName());
  }

  @Override
  public void close() {
    executor.shutdownNow();
    try {
      executor.awaitTermination(5, TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}

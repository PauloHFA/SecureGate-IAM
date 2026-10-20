package com.securegate.iam.audit.infrastructure;

import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.jdbc.core.JdbcTemplate;

/** Bounded owner-side DDL, independent of ingestion and of metric scrapes. */
public final class AuditPartitionMaintenance implements AutoCloseable {
  private final JdbcTemplate jdbc;
  private final MeterRegistry meters;
  private final Clock clock;
  private final int monthsAhead;
  private final int pollMillis;
  private volatile boolean available;
  private final ScheduledExecutorService executor =
      Executors.newSingleThreadScheduledExecutor(
          task -> {
            var thread = new Thread(task, "audit-partitions");
            thread.setDaemon(true);
            return thread;
          });

  public AuditPartitionMaintenance(
      JdbcTemplate jdbc, MeterRegistry meters, Clock clock, int monthsAhead, int pollMillis) {
    this.jdbc = jdbc;
    this.meters = meters;
    this.clock = clock;
    this.monthsAhead = monthsAhead;
    this.pollMillis = pollMillis;
    meters.gauge("iam.audit.partitions.available", this, worker -> worker.available ? 1 : 0);
  }

  public void start() {
    executor.scheduleWithFixedDelay(this::runOnce, 0, pollMillis, TimeUnit.MILLISECONDS);
  }

  public void runOnce() {
    try {
      LocalDate first =
          LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).withDayOfMonth(1).minusMonths(1);
      Integer created =
          jdbc.queryForObject(
              "SELECT iam_audit.ensure_event_partitions(?,?)",
              Integer.class,
              Date.valueOf(first),
              monthsAhead + 2);
      if (created == null) {
        throw new IllegalStateException();
      }
      available = true;
      meters.counter("iam.audit.partitions.created").increment(created);
      meters.counter("iam.audit.partitions.runs", "result", "success").increment();
    } catch (RuntimeException unavailable) {
      available = false;
      meters.counter("iam.audit.partitions.runs", "result", "unavailable").increment();
      org.slf4j.LoggerFactory.getLogger(AuditPartitionMaintenance.class)
          .warn(
              "Audit partition maintenance unavailable (type={})",
              unavailable.getClass().getSimpleName());
    }
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

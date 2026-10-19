package com.securegate.iam.audit.infrastructure;

import com.securegate.iam.audit.application.OutboxPublisher;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.LoggerFactory;

/** One bounded batch per tick; infrastructure errors never disable subsequent ticks. */
public final class OutboxWorker implements AutoCloseable {
  private final OutboxPublisher publisher;
  private final int batchSize;
  private final int pollMillis;
  private final ScheduledExecutorService executor =
      Executors.newSingleThreadScheduledExecutor(
          task -> {
            Thread thread = new Thread(task, "outbox-publisher");
            thread.setDaemon(true);
            return thread;
          });

  public OutboxWorker(OutboxPublisher publisher, int batchSize, int pollMillis) {
    this.publisher = publisher;
    this.batchSize = batchSize;
    this.pollMillis = pollMillis;
  }

  public void start() {
    executor.scheduleWithFixedDelay(
        () -> {
          try {
            publisher.publishBatch(batchSize);
          } catch (RuntimeException failure) {
            LoggerFactory.getLogger(OutboxWorker.class)
                .warn(
                    "Outbox publication unavailable (type={})", failure.getClass().getSimpleName());
          }
        },
        pollMillis,
        pollMillis,
        TimeUnit.MILLISECONDS);
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

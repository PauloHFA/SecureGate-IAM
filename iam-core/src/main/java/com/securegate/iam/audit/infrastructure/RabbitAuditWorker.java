package com.securegate.iam.audit.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.securegate.events.SecurityEvent;
import com.securegate.iam.audit.application.ArchivedAuditMonthException;
import com.securegate.iam.audit.application.port.AuditEventStore;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;

/**
 * One channel, bounded outstanding deliveries, atomic store batch and ACK strictly after commit.
 */
public final class RabbitAuditWorker implements AutoCloseable {
  public static final String QUEUE = "audit.persist";

  private record Pending(long tag, SecurityEvent event, AuditConsumerTrace.Delivery trace) {}

  private final ConnectionFactory factory;
  private final RabbitTopology topology;
  private final AuditMessageDecoder decoder;
  private final AuditEventStore store;
  private final MeterRegistry meters;
  private final int batchSize;
  private final int pollMillis;
  private final AuditConsumerTrace tracing;
  private final ScheduledExecutorService executor =
      Executors.newSingleThreadScheduledExecutor(
          task -> {
            var thread = new Thread(task, "audit-consumer");
            thread.setDaemon(true);
            return thread;
          });
  private Connection connection;
  private Channel channel;
  private boolean closed;

  public RabbitAuditWorker(
      ConnectionFactory factory,
      ObjectMapper json,
      AuditEventStore store,
      MeterRegistry meters,
      int batchSize,
      int pollMillis) {
    this(factory, json, store, meters, batchSize, pollMillis, new AuditConsumerTrace(null));
  }

  public RabbitAuditWorker(
      ConnectionFactory factory,
      ObjectMapper json,
      AuditEventStore store,
      MeterRegistry meters,
      int batchSize,
      int pollMillis,
      AuditConsumerTrace tracing) {
    this.tracing = tracing;
    if (batchSize < 1 || batchSize > 100 || pollMillis < 100 || pollMillis > 60000) {
      throw new IllegalArgumentException("Invalid audit consumer bounds.");
    }
    this.factory = factory;
    this.topology = new RabbitTopology(json);
    this.decoder = new AuditMessageDecoder(json);
    this.store = store;
    this.meters = meters;
    this.batchSize = batchSize;
    this.pollMillis = pollMillis;
  }

  public void start() {
    executor.scheduleWithFixedDelay(this::runOnce, 0, pollMillis, TimeUnit.MILLISECONDS);
  }

  public synchronized void runOnce() {
    if (closed || Thread.currentThread().isInterrupted()) {
      return;
    }
    try {
      connect();
      var pending = new ArrayList<Pending>();
      for (int i = 0; i < batchSize && !Thread.currentThread().isInterrupted(); i++) {
        var message = channel.basicGet(QUEUE, false);
        if (message == null) {
          break;
        }
        long tag = message.getEnvelope().getDeliveryTag();
        var trace = tracing.delivery(message.getProps(), message.getEnvelope().isRedeliver());
        SecurityEvent event;
        try {
          event = decoder.decode(message.getProps(), message.getBody());
        } catch (IllegalArgumentException invalid) {
          tracing.individual(
              trace,
              true,
              () -> {
                channel.basicReject(tag, false);
                count("INVALID");
                return AuditConsumerTrace.Outcome.INVALID;
              });
          continue;
        }
        pending.add(new Pending(tag, event, trace));
      }
      if (!pending.isEmpty()) {
        tracing.batch(pending.stream().map(Pending::trace).toList(), () -> persist(pending));
      }
    } catch (IOException | TimeoutException | RuntimeException unavailable) {
      // Closing requeues all unacknowledged deliveries, including an uncertain commit/ACK.
      disconnect();
      meters.counter("iam.audit.consumer.errors").increment();
      LoggerFactory.getLogger(RabbitAuditWorker.class)
          .warn("Audit consumer unavailable (type={})", unavailable.getClass().getSimpleName());
    }
  }

  private AuditConsumerTrace.Outcome persist(List<Pending> pending) throws IOException {
    List<AuditEventStore.Result> results;
    try {
      results = store.appendBatch(pending.stream().map(Pending::event).toList());
    } catch (DuplicateKeyException | ArchivedAuditMonthException conflict) {
      // The batch rolled back. Isolate the poison identity so adjacent valid events can progress.
      return isolate(pending);
    } catch (RuntimeException unavailable) {
      retry(pending);
      return AuditConsumerTrace.Outcome.RETRY;
    }
    if (results == null
        || results.size() != pending.size()
        || results.stream().anyMatch(java.util.Objects::isNull)) {
      throw new IllegalStateException("Invalid audit store result.");
    }
    for (int i = 0; i < pending.size(); i++) {
      channel.basicAck(pending.get(i).tag(), false);
      count(results.get(i).name());
    }
    meters.counter("iam.audit.consumer.batches").increment();
    return AuditConsumerTrace.Outcome.STORED;
  }

  private AuditConsumerTrace.Outcome isolate(List<Pending> pending) throws IOException {
    for (int i = 0; i < pending.size(); i++) {
      var message = pending.get(i);
      var remaining = pending.subList(i, pending.size());
      var outcome =
          tracing.individual(message.trace(), false, () -> isolateOne(message, remaining));
      if (outcome == AuditConsumerTrace.Outcome.RETRY) return outcome;
    }
    return AuditConsumerTrace.Outcome.ISOLATED;
  }

  private AuditConsumerTrace.Outcome isolateOne(Pending message, List<Pending> remaining)
      throws IOException {
    AuditEventStore.Result result;
    try {
      result = store.append(message.event());
      if (result == null) throw new IllegalStateException("Invalid audit store result.");
    } catch (DuplicateKeyException conflict) {
      channel.basicReject(message.tag(), false);
      count("CONFLICT");
      return AuditConsumerTrace.Outcome.CONFLICT;
    } catch (ArchivedAuditMonthException late) {
      channel.basicReject(message.tag(), false);
      count("LATE");
      return AuditConsumerTrace.Outcome.LATE;
    } catch (RuntimeException unavailable) {
      retry(remaining);
      return AuditConsumerTrace.Outcome.RETRY;
    }
    channel.basicAck(message.tag(), false);
    count(result.name());
    return result == AuditEventStore.Result.APPENDED
        ? AuditConsumerTrace.Outcome.APPENDED
        : AuditConsumerTrace.Outcome.DUPLICATE;
  }

  private void retry(List<Pending> pending) throws IOException {
    for (var message : pending) {
      channel.basicNack(message.tag(), false, true);
      count("RETRY");
    }
  }

  private void count(String result) {
    meters.counter("iam.audit.consumer.messages", "result", result).increment();
  }

  private void connect() throws IOException, TimeoutException {
    if (connection != null && connection.isOpen() && channel != null && channel.isOpen()) {
      return;
    }
    disconnect();
    connection = factory.newConnection("securegate-audit-consumer");
    channel = connection.createChannel();
    topology.declare(channel);
  }

  private void disconnect() {
    try {
      if (connection != null) {
        connection.abort();
      }
    } catch (RuntimeException ignored) {
      // A disconnected broker must not disable later ticks.
    } finally {
      connection = null;
      channel = null;
    }
  }

  @Override
  public void close() {
    executor.shutdownNow();
    synchronized (this) {
      closed = true;
      disconnect();
    }
    try {
      executor.awaitTermination(5, TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}

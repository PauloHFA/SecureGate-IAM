package com.securegate.enforcer.infrastructure;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.LoggerFactory;

/** Bounded pull batches with one unacked message; no concurrent use of a channel. */
public final class RabbitRevocationWorker implements AutoCloseable {
  public static final String QUEUE = "enforcer.revocation";
  private final ConnectionFactory factory;
  private final ObjectMapper json;
  private final RevocationMessageHandler handler;
  private final MeterRegistry meters;
  private final int batchSize;
  private final int pollMillis;
  private Connection connection;
  private Channel channel;
  private final ScheduledExecutorService executor =
      Executors.newSingleThreadScheduledExecutor(
          task -> {
            var thread = new Thread(task, "revocation-consumer");
            thread.setDaemon(false);
            return thread;
          });

  public RabbitRevocationWorker(
      ConnectionFactory factory,
      ObjectMapper json,
      RevocationMessageHandler handler,
      MeterRegistry meters,
      int batchSize,
      int pollMillis) {
    if (batchSize < 1 || batchSize > 100 || pollMillis < 100 || pollMillis > 60000) {
      throw new IllegalArgumentException("Invalid consumer bounds.");
    }
    this.factory = factory;
    this.json = json;
    this.handler = handler;
    this.meters = meters;
    this.batchSize = batchSize;
    this.pollMillis = pollMillis;
  }

  public void start() {
    executor.scheduleWithFixedDelay(this::runOnce, 0, pollMillis, TimeUnit.MILLISECONDS);
  }

  public synchronized void runOnce() {
    try {
      connect();
      for (int i = 0; i < batchSize && !Thread.currentThread().isInterrupted(); i++) {
        var message = channel.basicGet(QUEUE, false);
        if (message == null) {
          break;
        }
        var result = handler.handle(message.getProps(), message.getBody());
        long tag = message.getEnvelope().getDeliveryTag();
        switch (result) {
          case ACK -> channel.basicAck(tag, false);
          case REJECT -> channel.basicReject(tag, false);
          case RETRY -> channel.basicNack(tag, false, true);
        }
        meters.counter("iam.enforcer.revocation.messages", "result", result.name()).increment();
        if (result == RevocationMessageHandler.Result.RETRY) {
          break;
        }
      }
    } catch (IOException | TimeoutException | RuntimeException unavailable) {
      disconnect();
      meters.counter("iam.enforcer.revocation.connection.errors").increment();
      LoggerFactory.getLogger(RabbitRevocationWorker.class)
          .warn(
              "Revocation consumer unavailable (type={})", unavailable.getClass().getSimpleName());
    }
  }

  private void connect() throws IOException, TimeoutException {
    if (connection != null && connection.isOpen() && channel != null && channel.isOpen()) {
      return;
    }
    disconnect();
    connection = factory.newConnection("securegate-revocation");
    channel = connection.createChannel();
    try (var input = getClass().getResourceAsStream("/rabbitmq/definitions.json")) {
      if (input == null) {
        throw new IOException("Missing topology.");
      }
      var topology = json.readTree(input);
      for (var exchange : topology.path("exchanges")) {
        channel.exchangeDeclare(
            exchange.path("name").asText(), exchange.path("type").asText(), true);
      }
      for (var queue : topology.path("queues")) {
        if (queue.path("name").asText().startsWith(QUEUE)) {
          Map<String, Object> arguments =
              json.convertValue(queue.path("arguments"), new TypeReference<>() {});
          channel.queueDeclare(queue.path("name").asText(), true, false, false, arguments);
        }
      }
      for (var binding : topology.path("bindings")) {
        if (binding.path("destination").asText().startsWith(QUEUE)) {
          channel.queueBind(
              binding.path("destination").asText(),
              binding.path("source").asText(),
              binding.path("routing_key").asText());
        }
      }
    }
  }

  private void disconnect() {
    try {
      if (connection != null) {
        connection.abort();
      }
    } catch (RuntimeException ignored) {
      /* A broken connection must not disable later ticks. */
    } finally {
      connection = null;
      channel = null;
    }
  }

  @Override
  public void close() {
    executor.shutdownNow();
    synchronized (this) {
      disconnect();
    }
    try {
      executor.awaitTermination(5, TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}

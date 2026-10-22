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
  public static final String BLOCK_POLICY_QUEUE = "enforcer.block-policy";
  private final java.util.function.BiFunction<
          com.rabbitmq.client.AMQP.BasicProperties, byte[], RevocationMessageHandler.Result>
      handler;
  private final String queueName;
  private final String metricPrefix;
  private final MeterRegistry meters;
  private final int batchSize;
  private final int pollMillis;
  private final ConsumerTrace tracing;
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
    this(factory, json, handler::handle, meters, batchSize, pollMillis, QUEUE);
  }

  public RabbitRevocationWorker(
      ConnectionFactory factory,
      ObjectMapper json,
      java.util.function.BiFunction<
              com.rabbitmq.client.AMQP.BasicProperties, byte[], RevocationMessageHandler.Result>
          handler,
      MeterRegistry meters,
      int batchSize,
      int pollMillis,
      String queueName) {
    this(factory, json, handler, meters, batchSize, pollMillis, queueName, new ConsumerTrace(null));
  }

  public RabbitRevocationWorker(
      ConnectionFactory factory,
      ObjectMapper json,
      java.util.function.BiFunction<
              com.rabbitmq.client.AMQP.BasicProperties, byte[], RevocationMessageHandler.Result>
          handler,
      MeterRegistry meters,
      int batchSize,
      int pollMillis,
      String queueName,
      ConsumerTrace tracing) {
    this.tracing = tracing;
    if (!QUEUE.equals(queueName) && !BLOCK_POLICY_QUEUE.equals(queueName)) {
      throw new IllegalArgumentException("Unsupported queue.");
    }
    if (batchSize < 1 || batchSize > 100 || pollMillis < 100 || pollMillis > 60000) {
      throw new IllegalArgumentException("Invalid consumer bounds.");
    }
    this.factory = factory;
    this.json = json;
    this.handler = handler;
    this.meters = meters;
    this.batchSize = batchSize;
    this.pollMillis = pollMillis;
    this.queueName = queueName;
    this.metricPrefix =
        QUEUE.equals(queueName) ? "iam.enforcer.revocation" : "iam.enforcer.block-policy";
  }

  public void start() {
    executor.scheduleWithFixedDelay(this::runOnce, 0, pollMillis, TimeUnit.MILLISECONDS);
  }

  public synchronized void runOnce() {
    try {
      connect();
      for (int i = 0; i < batchSize && !Thread.currentThread().isInterrupted(); i++) {
        var message = channel.basicGet(queueName, false);
        if (message == null) {
          break;
        }
        var result =
            tracing.consume(
                message.getProps(),
                message.getEnvelope().isRedeliver(),
                BLOCK_POLICY_QUEUE.equals(queueName),
                () -> {
                  var handled = handler.apply(message.getProps(), message.getBody());
                  long tag = message.getEnvelope().getDeliveryTag();
                  switch (handled) {
                    case ACK -> channel.basicAck(tag, false);
                    case REJECT -> channel.basicReject(tag, false);
                    case RETRY -> channel.basicNack(tag, false, true);
                  }
                  meters.counter(metricPrefix + ".messages", "result", handled.name()).increment();
                  return handled;
                });
        if (result == RevocationMessageHandler.Result.RETRY) {
          break;
        }
      }
    } catch (IOException | TimeoutException | RuntimeException unavailable) {
      disconnect();
      meters.counter(metricPrefix + ".connection.errors").increment();
      LoggerFactory.getLogger(RabbitRevocationWorker.class)
          .warn(
              "Security event consumer unavailable (queue={}, type={})",
              queueName,
              unavailable.getClass().getSimpleName());
    }
  }

  private void connect() throws IOException, TimeoutException {
    if (connection != null && connection.isOpen() && channel != null && channel.isOpen()) {
      return;
    }
    disconnect();
    connection = factory.newConnection("securegate-" + queueName);
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
        if (queue.path("name").asText().startsWith(queueName)) {
          Map<String, Object> arguments =
              json.convertValue(queue.path("arguments"), new TypeReference<>() {});
          channel.queueDeclare(queue.path("name").asText(), true, false, false, arguments);
        }
      }
      for (var binding : topology.path("bindings")) {
        if (binding.path("destination").asText().startsWith(queueName)) {
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

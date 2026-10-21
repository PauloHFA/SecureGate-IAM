package com.securegate.iam.audit.infrastructure;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.securegate.events.SecurityEvent;
import com.securegate.iam.audit.application.port.EventTransport;
import com.securegate.iam.audit.application.port.OutboxDelivery;
import com.securegate.iam.audit.application.port.OutboxTraceParent;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RabbitEventTransport implements EventTransport, AutoCloseable {
  public static final String EXCHANGE = "security.events";
  private final ConnectionFactory factory;
  private final RabbitTopology topology;
  private final ObjectReader events;
  private final int timeoutMillis;
  private Connection connection;
  private final Tracer tracer;

  public RabbitEventTransport(ConnectionFactory factory, ObjectMapper json, int timeoutMillis) {
    this(factory, json, timeoutMillis, null);
  }

  public RabbitEventTransport(
      ConnectionFactory factory, ObjectMapper json, int timeoutMillis, Tracer tracer) {
    this.tracer = tracer;
    if (timeoutMillis < 100 || timeoutMillis > 5000) {
      throw new IllegalArgumentException("Invalid confirmation timeout.");
    }
    this.factory = factory;
    this.topology = new RabbitTopology(json);
    this.events =
        json.readerFor(SecurityEvent.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    this.timeoutMillis = timeoutMillis;
  }

  @Override
  public Outcome publish(OutboxDelivery.Claim claim) {
    if (tracer == null) return publish(claim, null);
    // Explicit parent (or root) prevents a worker's ambient span from replacing durable context.
    var builder = tracer.spanBuilder().name("outbox.publish").kind(Span.Kind.PRODUCER);
    String parent = OutboxTraceParent.safeOrNull(claim.traceParent());
    if (parent == null) {
      builder.setNoParent();
    } else {
      builder.setParent(
          tracer
              .traceContextBuilder()
              .traceId(parent.substring(3, 35))
              .spanId(parent.substring(36, 52))
              .sampled(parent.endsWith("01"))
              .build());
    }
    Span span = builder.start();
    Outcome outcome = Outcome.BROKER_UNAVAILABLE;
    try (var scope = tracer.withSpan(span)) {
      var context = span.context();
      String header =
          OutboxTraceParent.safeOrNull(
              "00-"
                  + context.traceId()
                  + "-"
                  + context.spanId()
                  + (Boolean.TRUE.equals(context.sampled()) ? "-01" : "-00"));
      outcome = publish(claim, header);
      return outcome;
    } finally {
      span.tag("messaging.system", "rabbitmq");
      span.tag("messaging.outcome", outcome.name().toLowerCase(Locale.ROOT));
      span.end();
    }
  }

  private Outcome publish(OutboxDelivery.Claim claim, String traceParent) {
    SecurityEvent event;
    try {
      event = events.readValue(claim.payload());
      if (!event.eventId().equals(claim.eventId())
          || !event.eventType().equals(claim.eventType())
          || event.schemaVersion() != claim.schemaVersion()) {
        return Outcome.INVALID_EVENT;
      }
    } catch (IOException | RuntimeException invalid) {
      return Outcome.INVALID_EVENT;
    }
    try (var channel = connection().createChannel()) {
      var returned = new AtomicBoolean();
      channel.addReturnListener(message -> returned.set(true));
      channel.confirmSelect();
      var headers = new HashMap<String, Object>();
      headers.put("schemaVersion", event.schemaVersion());
      if (traceParent != null) headers.put("traceparent", traceParent);
      var properties =
          new AMQP.BasicProperties.Builder()
              .messageId(event.eventId().toString())
              .correlationId(event.correlationId())
              .type(event.eventType())
              .appId(event.producer())
              .timestamp(Date.from(event.occurredAt()))
              .contentType("application/json")
              .contentEncoding("UTF-8")
              .deliveryMode(2)
              .headers(headers)
              .build();
      channel.basicPublish(
          EXCHANGE,
          event.eventType(),
          true,
          properties,
          claim.payload().getBytes(StandardCharsets.UTF_8));
      boolean acked = channel.waitForConfirms(timeoutMillis);
      // RabbitMQ sends a mandatory return before the corresponding publisher confirmation.
      return returned.get() ? Outcome.UNROUTABLE : acked ? Outcome.ACKED : Outcome.NACK;
    } catch (TimeoutException timeout) {
      return Outcome.CONFIRM_TIMEOUT;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return Outcome.CONFIRM_TIMEOUT;
    } catch (IOException | RuntimeException unavailable) {
      return Outcome.BROKER_UNAVAILABLE;
    }
  }

  private synchronized Connection connection() throws IOException, TimeoutException {
    if (connection == null || !connection.isOpen()) {
      Connection candidate = factory.newConnection("securegate-outbox");
      try {
        try (var channel = candidate.createChannel()) {
          topology.declare(channel);
        }
        connection = candidate;
      } catch (IOException | TimeoutException | RuntimeException failure) {
        candidate.abort();
        throw failure;
      }
    }
    return connection;
  }

  @Override
  public synchronized void close() {
    if (connection != null) {
      connection.abort();
      connection = null;
    }
  }
}

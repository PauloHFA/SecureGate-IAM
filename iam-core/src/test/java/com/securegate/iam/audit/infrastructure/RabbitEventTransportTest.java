package com.securegate.iam.audit.infrastructure;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.ReturnCallback;
import com.securegate.events.SecurityEvent;
import com.securegate.iam.audit.application.port.EventTransport;
import com.securegate.iam.audit.application.port.OutboxDelivery;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.*;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RabbitEventTransportTest {
  private final ConnectionFactory factory = mock(ConnectionFactory.class);
  private final Connection connection = mock(Connection.class);
  private final Channel topology = mock(Channel.class);
  private final Channel channel = mock(Channel.class);
  private final ObjectMapper json =
      new ObjectMapper()
          .findAndRegisterModules()
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  private RabbitEventTransport transport;
  private OutboxDelivery.Claim claim;

  @BeforeEach
  void prepare() throws Exception {
    doReturn(connection).when(factory).newConnection(anyString());
    when(connection.isOpen()).thenReturn(true);
    when(connection.createChannel()).thenReturn(topology, channel);
    UUID id = UUID.randomUUID();
    var event =
        new SecurityEvent(
            id,
            "test.outbox",
            1,
            Instant.now(),
            "iam-core",
            "unit-test",
            "test",
            Map.of("resource", "synthetic"),
            Map.of());
    claim =
        new OutboxDelivery.Claim(
            id, event.eventType(), 1, json.writeValueAsString(event), 1, UUID.randomUUID());
    transport = new RabbitEventTransport(factory, json, 2000);
  }

  @Test
  void nackAndConfirmTimeoutNeverBecomeAck() throws Exception {
    when(channel.waitForConfirms(2000)).thenReturn(false);
    assertEquals(EventTransport.Outcome.NACK, transport.publish(claim));
    when(channel.waitForConfirms(2000)).thenThrow(new TimeoutException());
    assertEquals(EventTransport.Outcome.CONFIRM_TIMEOUT, transport.publish(claim));
    verify(factory, times(1)).newConnection(anyString());
    transport.close();
    verify(connection).abort();
  }

  @Test
  void interruptionPreservesTheFlagAndDoesNotAcknowledge() throws Exception {
    when(channel.waitForConfirms(2000)).thenThrow(new InterruptedException());
    try {
      assertEquals(EventTransport.Outcome.CONFIRM_TIMEOUT, transport.publish(claim));
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void malformedPayloadAndMismatchedIdentityAreRejectedBeforeNetworkUse() {
    var invalid =
        new OutboxDelivery.Claim(
            claim.eventId(), claim.eventType(), 1, "not-json", 1, claim.claimId());
    assertEquals(EventTransport.Outcome.INVALID_EVENT, transport.publish(invalid));
    var mismatch =
        new OutboxDelivery.Claim(
            UUID.randomUUID(), claim.eventType(), 1, claim.payload(), 1, claim.claimId());
    assertEquals(EventTransport.Outcome.INVALID_EVENT, transport.publish(mismatch));
    verifyNoInteractions(factory);
  }

  @Test
  void connectionAndTopologyErrorsAbortTheIncompleteConnection() throws Exception {
    when(factory.newConnection(anyString())).thenThrow(new IOException("synthetic"));
    assertEquals(EventTransport.Outcome.BROKER_UNAVAILABLE, transport.publish(claim));
    doReturn(connection).when(factory).newConnection(anyString());
    doThrow(new IOException("synthetic topology failure"))
        .when(topology)
        .exchangeDeclare(
            anyString(), anyString(), anyBoolean(), anyBoolean(), anyBoolean(), anyMap());
    assertEquals(EventTransport.Outcome.BROKER_UNAVAILABLE, transport.publish(claim));
    verify(connection).abort();
  }

  @Test
  void returnIsFailureEvenIfBrokerConfirms() throws Exception {
    var callback = ArgumentCaptor.forClass(ReturnCallback.class);
    doAnswer(invocation -> null).when(channel).addReturnListener(callback.capture());
    doAnswer(
            invocation -> {
              callback.getValue().handle(null);
              return null;
            })
        .when(channel)
        .basicPublish(anyString(), anyString(), eq(true), any(), any(byte[].class));
    when(channel.waitForConfirms(2000)).thenReturn(true);
    assertEquals(EventTransport.Outcome.UNROUTABLE, transport.publish(claim));
  }

  private OutboxDelivery.Claim withParent(String parent) {
    return new OutboxDelivery.Claim(
        claim.eventId(),
        claim.eventType(),
        claim.schemaVersion(),
        claim.payload(),
        claim.attempts(),
        claim.claimId(),
        parent);
  }

  private AMQP.BasicProperties sentProperties() throws Exception {
    var properties = ArgumentCaptor.forClass(AMQP.BasicProperties.class);
    verify(channel, atLeastOnce())
        .basicPublish(
            eq("security.events"),
            eq(claim.eventType()),
            eq(true),
            properties.capture(),
            eq(claim.payload().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    return properties.getValue();
  }

  @Test
  void disabledTracingDoesNotPublishEvenPersistedParent() throws Exception {
    when(channel.waitForConfirms(2000)).thenReturn(true);
    assertEquals(
        EventTransport.Outcome.ACKED,
        transport.publish(withParent("00-" + "1".repeat(32) + "-" + "2".repeat(16) + "-01")));
    assertEquals(Map.of("schemaVersion", 1), sentProperties().getHeaders());
  }

  @Test
  void retriesUseDurableParentAndDifferentProducerSpansWithoutLeakingAmbientContext()
      throws Exception {
    try (var tracing = new Tracing(Sampler.alwaysOn())) {
      transport = new RabbitEventTransport(factory, json, 2000, tracing.tracer);
      String parent = "00-" + "1".repeat(32) + "-" + "2".repeat(16) + "-01";
      var ambient = tracing.tracer.nextSpan().name("worker").start();
      when(channel.waitForConfirms(2000)).thenReturn(false, true);
      String first;
      String second;
      try (var scope = tracing.tracer.withSpan(ambient)) {
        assertEquals(EventTransport.Outcome.NACK, transport.publish(withParent(parent)));
        first = (String) sentProperties().getHeaders().get("traceparent");
        assertEquals(EventTransport.Outcome.ACKED, transport.publish(withParent(parent)));
        second = (String) sentProperties().getHeaders().get("traceparent");
        assertEquals(ambient.context().spanId(), tracing.tracer.currentSpan().context().spanId());
      } finally {
        ambient.end();
      }
      assertNull(tracing.tracer.currentSpan());
      assertNotEquals(first, second);
      assertTrue(first.startsWith("00-" + "1".repeat(32) + "-"));
      assertEquals(
          java.util.Set.of("schemaVersion", "traceparent"), sentProperties().getHeaders().keySet());
      var attempts =
          tracing.spans.stream().filter(it -> it.getName().equals("outbox.publish")).toList();
      assertEquals(2, attempts.size());
      for (var span : attempts) {
        assertEquals("2".repeat(16), span.getParentSpanId());
        assertEquals(io.opentelemetry.api.trace.SpanKind.PRODUCER, span.getKind());
        assertEquals(2, span.getAttributes().size());
        assertTrue(span.getEvents().isEmpty());
        assertEquals(
            "rabbitmq", span.getAttributes().get(AttributeKey.stringKey("messaging.system")));
      }
      assertEquals(
          "nack", attempts.get(0).getAttributes().get(AttributeKey.stringKey("messaging.outcome")));
      assertEquals(
          "acked",
          attempts.get(1).getAttributes().get(AttributeKey.stringKey("messaging.outcome")));
    }
  }

  @Test
  void absentAndHostileParentsCreateIndependentRootsAndRestoreScope() throws Exception {
    try (var tracing = new Tracing(Sampler.alwaysOn())) {
      transport = new RabbitEventTransport(factory, json, 2000, tracing.tracer);
      when(channel.waitForConfirms(2000)).thenReturn(true);
      var ambient = tracing.tracer.nextSpan().name("worker").start();
      try (var scope = tracing.tracer.withSpan(ambient)) {
        for (String parent :
            new String[] {
              null,
              "hostile\r\nbaggage=synthetic",
              "00-" + "0".repeat(32) + "-" + "2".repeat(16) + "-01"
            }) {
          assertEquals(EventTransport.Outcome.ACKED, transport.publish(withParent(parent)));
          String header = (String) sentProperties().getHeaders().get("traceparent");
          assertNotEquals(ambient.context().traceId(), header.substring(3, 35));
          assertEquals(ambient.context().spanId(), tracing.tracer.currentSpan().context().spanId());
        }
      } finally {
        ambient.end();
      }
      for (var span :
          tracing.spans.stream().filter(it -> it.getName().equals("outbox.publish")).toList()) {
        assertEquals("0".repeat(16), span.getParentSpanId());
      }
      assertNull(tracing.tracer.currentSpan());
    }
  }

  @Test
  void localZeroSamplingOverridesPersistedSampledBitButStillPropagates() throws Exception {
    try (var tracing = new Tracing(Sampler.traceIdRatioBased(0))) {
      transport = new RabbitEventTransport(factory, json, 2000, tracing.tracer);
      when(channel.waitForConfirms(2000)).thenReturn(true);
      assertEquals(
          EventTransport.Outcome.ACKED,
          transport.publish(withParent("00-" + "1".repeat(32) + "-" + "2".repeat(16) + "-01")));
      assertTrue(((String) sentProperties().getHeaders().get("traceparent")).endsWith("-00"));
      assertTrue(tracing.spans.isEmpty());
      assertNull(tracing.tracer.currentSpan());
    }
  }

  @Test
  void tracingClosesOnTimeoutInterruptionConnectionFailureAndInvalidPayload() throws Exception {
    try (var tracing = new Tracing(Sampler.alwaysOn())) {
      transport = new RabbitEventTransport(factory, json, 2000, tracing.tracer);
      when(channel.waitForConfirms(2000))
          .thenThrow(new TimeoutException(), new InterruptedException());
      assertEquals(EventTransport.Outcome.CONFIRM_TIMEOUT, transport.publish(claim));
      try {
        assertEquals(EventTransport.Outcome.CONFIRM_TIMEOUT, transport.publish(claim));
        assertTrue(Thread.currentThread().isInterrupted());
      } finally {
        Thread.interrupted();
      }
      when(connection.createChannel()).thenThrow(new IOException("synthetic-private-error"));
      assertEquals(EventTransport.Outcome.BROKER_UNAVAILABLE, transport.publish(claim));
      var invalid =
          new OutboxDelivery.Claim(
              claim.eventId(), claim.eventType(), 1, "private-invalid-json", 1, claim.claimId());
      assertEquals(EventTransport.Outcome.INVALID_EVENT, transport.publish(invalid));
      assertNull(tracing.tracer.currentSpan());
      assertEquals(
          List.of("confirm_timeout", "confirm_timeout", "broker_unavailable", "invalid_event"),
          tracing.spans.stream()
              .map(it -> it.getAttributes().get(AttributeKey.stringKey("messaging.outcome")))
              .toList());
      for (var span : tracing.spans) {
        assertEquals(2, span.getAttributes().size());
        assertTrue(span.getEvents().isEmpty());
      }
    }
  }

  private static class Tracing implements AutoCloseable {
    final List<SpanData> spans = new ArrayList<>();
    final SdkTracerProvider provider;
    final OtelTracer tracer;

    Tracing(Sampler sampler) {
      provider =
          SdkTracerProvider.builder()
              .setSampler(sampler)
              .addSpanProcessor(
                  new SpanProcessor() {
                    public void onStart(Context parent, ReadWriteSpan span) {}

                    public boolean isStartRequired() {
                      return false;
                    }

                    public void onEnd(ReadableSpan span) {
                      spans.add(span.toSpanData());
                    }

                    public boolean isEndRequired() {
                      return true;
                    }
                  })
              .build();
      tracer = new OtelTracer(provider.get("test"), new OtelCurrentTraceContext(), event -> {});
    }

    public void close() {
      provider.close();
    }
  }
}

package com.securegate.iam.audit.infrastructure;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.ReturnCallback;
import com.securegate.events.SecurityEvent;
import com.securegate.iam.audit.application.port.EventTransport;
import com.securegate.iam.audit.application.port.OutboxDelivery;
import java.io.IOException;
import java.time.Instant;
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
}

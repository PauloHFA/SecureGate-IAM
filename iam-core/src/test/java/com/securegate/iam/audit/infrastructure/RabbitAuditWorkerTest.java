package com.securegate.iam.audit.infrastructure;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.rabbitmq.client.*;
import com.securegate.events.SecurityEvent;
import com.securegate.iam.audit.application.port.AuditEventStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;

class RabbitAuditWorkerTest {
  private final ObjectMapper json =
      new ObjectMapper()
          .findAndRegisterModules()
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  private final ConnectionFactory factory = mock(ConnectionFactory.class);
  private final Connection connection = mock(Connection.class);
  private final Channel channel = mock(Channel.class);
  private final AuditEventStore store = mock(AuditEventStore.class);
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private SecurityEvent first;
  private SecurityEvent second;

  @BeforeEach
  void prepare() throws Exception {
    doReturn(connection).when(factory).newConnection(anyString());
    when(connection.isOpen()).thenReturn(true);
    when(channel.isOpen()).thenReturn(true);
    when(connection.createChannel()).thenReturn(channel);
    first = event();
    second = event();
    when(channel.basicGet(RabbitAuditWorker.QUEUE, false))
        .thenReturn(message(first, 1), message(second, 2), null);
  }

  @Test
  void acknowledgesOnlyAfterOneSuccessfulBatchAndClosesPermanently() throws Exception {
    when(store.appendBatch(List.of(first, second)))
        .thenReturn(List.of(AuditEventStore.Result.APPENDED, AuditEventStore.Result.DUPLICATE));
    try (var worker = worker()) {
      worker.runOnce();
      var order = inOrder(store, channel);
      order.verify(store).appendBatch(List.of(first, second));
      order.verify(channel).basicAck(1, false);
      order.verify(channel).basicAck(2, false);
      verify(store, never()).append(any());
      assertEquals(1, meters.get("iam.audit.consumer.batches").counter().count());
    }
    verify(connection).abort();
  }

  @Test
  void transientFailureRequeuesWholeBatchWithoutAckOrReject() throws Exception {
    when(store.appendBatch(anyList()))
        .thenThrow(new DataAccessResourceFailureException("synthetic"));
    try (var worker = worker()) {
      worker.runOnce();
      verify(channel).basicNack(1, false, true);
      verify(channel).basicNack(2, false, true);
      verify(channel, never()).basicAck(anyLong(), anyBoolean());
      verify(channel, never()).basicReject(anyLong(), anyBoolean());
    }
  }

  @Test
  void rolledBackConflictIsIsolatedAndTransientFailureStopsFallback() throws Exception {
    when(store.appendBatch(anyList())).thenThrow(new DuplicateKeyException("synthetic"));
    when(store.append(first)).thenThrow(new DuplicateKeyException("synthetic"));
    when(store.append(second)).thenThrow(new DataAccessResourceFailureException("synthetic"));
    try (var worker = worker()) {
      worker.runOnce();
      verify(channel).basicReject(1, false);
      verify(channel).basicNack(2, false, true);
      verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }
  }

  @Test
  void uncertainAckClosesConnectionAndReconnectsWithoutRejectingCommittedEvents() throws Exception {
    when(store.appendBatch(anyList()))
        .thenReturn(List.of(AuditEventStore.Result.APPENDED, AuditEventStore.Result.APPENDED));
    doThrow(new IOException("synthetic")).when(channel).basicAck(1, false);
    try (var worker = worker()) {
      worker.runOnce();
      verify(connection).abort();
      worker.runOnce();
      verify(factory, times(2)).newConnection(anyString());
      verify(channel, never()).basicReject(anyLong(), anyBoolean());
      verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }
  }

  @Test
  void malformedInputIsRejectedWithoutDatabaseAndBoundsAreValidated() throws Exception {
    when(channel.basicGet(RabbitAuditWorker.QUEUE, false))
        .thenReturn(new GetResponse(new Envelope(1, false, "", ""), null, new byte[0], 0), null);
    try (var worker = worker()) {
      worker.runOnce();
      verify(channel).basicReject(1, false);
      verifyNoInteractions(store);
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> new RabbitAuditWorker(factory, json, store, meters, 101, 1000));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RabbitAuditWorker(factory, json, store, meters, 1, 99));
  }

  @Test
  void decoderRejectsConflictingHeadersTrailingOrDuplicateFieldsAndOversize() throws Exception {
    var decoder = new AuditMessageDecoder(json);
    var valid = message(first, 1);
    assertEquals(first, decoder.decode(valid.getProps(), valid.getBody()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            decoder.decode(
                valid.getProps().builder().messageId(UUID.randomUUID().toString()).build(),
                valid.getBody()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            decoder.decode(
                valid.getProps(),
                (json.writeValueAsString(first) + " {}")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            decoder.decode(
                valid.getProps(),
                json.writeValueAsString(first)
                    .replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    assertThrows(
        IllegalArgumentException.class, () -> decoder.decode(valid.getProps(), new byte[65537]));
    assertThrows(
        IllegalArgumentException.class,
        () -> decoder.decode(valid.getProps().builder().timestamp(null).build(), valid.getBody()));
  }

  private RabbitAuditWorker worker() {
    return new RabbitAuditWorker(factory, json, store, meters, 20, 1000);
  }

  private GetResponse message(SecurityEvent event, long tag) throws Exception {
    return new GetResponse(
        new Envelope(tag, false, "security.events", event.eventType()),
        new AMQP.BasicProperties.Builder()
            .contentType("application/json")
            .contentEncoding("UTF-8")
            .deliveryMode(2)
            .messageId(event.eventId().toString())
            .type(event.eventType())
            .appId(event.producer())
            .correlationId(event.correlationId())
            .timestamp(Date.from(event.occurredAt()))
            .headers(Map.of("schemaVersion", 1))
            .build(),
        json.writeValueAsBytes(event),
        0);
  }

  private SecurityEvent event() {
    return new SecurityEvent(
        UUID.randomUUID(),
        "test.audit",
        1,
        Instant.now(),
        "iam-core",
        "synthetic",
        "synthetic",
        Map.of("resource", "synthetic"),
        Map.of());
  }
}

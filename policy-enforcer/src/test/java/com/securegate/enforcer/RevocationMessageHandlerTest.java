package com.securegate.enforcer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securegate.enforcer.application.RevocationHandler;
import com.securegate.enforcer.application.port.RevocationStore;
import com.securegate.enforcer.bootstrap.ConsumerProperties;
import com.securegate.enforcer.domain.Revocation;
import com.securegate.enforcer.infrastructure.RevocationMessageHandler;
import com.securegate.enforcer.infrastructure.RevocationMessageHandler.Result;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RevocationMessageHandlerTest {
  private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
  private final RevocationStore store = mock(RevocationStore.class);
  private final RevocationMessageHandler handler =
      new RevocationMessageHandler(json, new RevocationHandler(store));

  @Test
  void ackRequiresAppliedOrDuplicateAndConflictIsRejected() throws Exception {
    var event =
        TestEvents.token(
            UUID.randomUUID(), "ACCESS_TOKEN", "synthetic-jti", Instant.now().plusSeconds(60));
    byte[] body = json.writeValueAsBytes(event);
    when(store.apply(any(), any()))
        .thenReturn(
            RevocationStore.Result.APPLIED,
            RevocationStore.Result.DUPLICATE,
            RevocationStore.Result.CONFLICT,
            null)
        .thenThrow(new IllegalStateException("synthetic unavailable"));
    assertEquals(Result.ACK, handler.handle(TestEvents.properties(event), body));
    assertEquals(Result.ACK, handler.handle(TestEvents.properties(event), body));
    assertEquals(Result.REJECT, handler.handle(TestEvents.properties(event), body));
    assertEquals(Result.RETRY, handler.handle(TestEvents.properties(event), body));
    assertEquals(Result.RETRY, handler.handle(TestEvents.properties(event), body));
  }

  @Test
  void malformedUnsupportedAndMismatchedMessagesNeverReachStore() throws Exception {
    var event =
        TestEvents.token(
            UUID.randomUUID(), "AUTHORIZATION", "test-family", Instant.now().plusSeconds(60));
    byte[] body = json.writeValueAsBytes(event);
    var properties = TestEvents.properties(event);
    assertEquals(Result.REJECT, handler.handle(properties, new byte[65537]));
    assertEquals(Result.REJECT, handler.handle(properties, new byte[0]));
    assertEquals(
        Result.REJECT,
        handler.handle(properties, "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    assertEquals(Result.REJECT, handler.handle(null, body));
    assertEquals(
        Result.REJECT,
        handler.handle(properties.builder().messageId(UUID.randomUUID().toString()).build(), body));
    assertEquals(
        Result.REJECT,
        handler.handle(properties.builder().headers(Map.of("schemaVersion", 2)).build(), body));
    assertEquals(Result.REJECT, handler.handle(properties.builder().deliveryMode(1).build(), body));
    assertEquals(
        Result.REJECT,
        handler.handle(properties.builder().type("session.terminated").build(), body));
    assertEquals(
        Result.REJECT,
        handler.handle(properties.builder().contentType("text/plain").build(), body));
    var unknown = json.readTree(body).deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) unknown).put("unexpected", true);
    assertEquals(Result.REJECT, handler.handle(properties, json.writeValueAsBytes(unknown)));
    String trailing = json.writeValueAsString(event) + " {}";
    assertEquals(
        Result.REJECT,
        handler.handle(properties, trailing.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    var producer =
        new com.securegate.events.SecurityEvent(
            event.eventId(),
            event.eventType(),
            1,
            event.occurredAt(),
            "untrusted",
            event.correlationId(),
            event.actor(),
            event.context(),
            event.data());
    assertEquals(
        Result.REJECT,
        handler.handle(TestEvents.properties(producer), json.writeValueAsBytes(producer)));
    verifyNoInteractions(store);
  }

  @Test
  void terminalFactsUseSeparateTargetsAndNeverExposeTheirIdentifiersInDiagnostics() {
    var deadline = Instant.now().plusSeconds(60);
    var individual =
        Revocation.from(TestEvents.token(UUID.randomUUID(), "ACCESS_TOKEN", "test-jti", deadline));
    var family =
        Revocation.from(
            TestEvents.token(UUID.randomUUID(), "AUTHORIZATION", "test-family", deadline));
    var sid = Revocation.from(TestEvents.session(UUID.randomUUID()));
    assertEquals(Revocation.Kind.JTI, individual.kind());
    assertEquals("test-jti", individual.target());
    assertEquals(deadline.toEpochMilli() + 60000, individual.expiresAtMillis());
    assertEquals(Revocation.Kind.AUTHORIZATION, family.kind());
    assertEquals(Revocation.Kind.SID, sid.kind());
    assertEquals(0, sid.expiresAtMillis());
    assertFalse(individual.toString().contains(individual.target()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Revocation(UUID.randomUUID(), Revocation.Kind.JTI, "test", 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Revocation(UUID.randomUUID(), Revocation.Kind.SID, "test", 100));
  }

  @Test
  void settingsRequireCredentialsAndBoundedWork() {
    var password = UUID.randomUUID().toString();
    var settings =
        new ConsumerProperties("localhost", 5672, "securegate", "test", password, 20, 500);
    assertFalse(settings.toString().contains(password));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ConsumerProperties("localhost", 0, "securegate", "test", password, 20, 500));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ConsumerProperties("localhost", 5672, "securegate", "test", "", 20, 500));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ConsumerProperties("localhost", 5672, "securegate", "test", password, 101, 500));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ConsumerProperties("localhost", 5672, "securegate", "test", password, 20, 99));
  }
}

package com.securegate.enforcer;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securegate.enforcer.application.BlockPolicyHandler;
import com.securegate.enforcer.application.port.BlockPolicyStore;
import com.securegate.enforcer.bootstrap.BlockPolicyProperties;
import com.securegate.enforcer.domain.BlockPolicyEffect;
import com.securegate.enforcer.infrastructure.BlockPolicyMessageHandler;
import com.securegate.enforcer.infrastructure.RevocationMessageHandler.Result;
import com.securegate.events.SecurityEvent;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BlockPolicyEffectTest {
  static SecurityEvent event(String type, String resource, Map<String, String> data, Instant time) {
    return new SecurityEvent(
        UUID.randomUUID(),
        type,
        1,
        time,
        "iam-core",
        "test-correlation",
        "synthetic",
        Map.of("resource", resource),
        data);
  }

  @Test
  void mapsAllSupportedFactsWithoutInventingIdentityOrTreatingRiskAsAnAdministrativeBlock() {
    String id = UUID.randomUUID().toString();
    var identity =
        BlockPolicyEffect.from(
            event(
                "identity.user.updated",
                id,
                Map.of("status", "ACTIVE", "version", "9223372036854775807"),
                Instant.now()));
    assertEquals(BlockPolicyEffect.Kind.IDENTITY, identity.kind());
    assertEquals("ACTIVE", identity.state());
    var locked =
        BlockPolicyEffect.from(
            event(
                "user.locked",
                id,
                Map.of(
                    "userId", id, "status", "LOCKED", "version", "2", "reason", "ADMINISTRATIVE"),
                Instant.now()));
    assertEquals("LOCKED", locked.state());
    var policy =
        BlockPolicyEffect.from(
            event(
                "policy.changed",
                id,
                Map.of(
                    "policyId",
                    id,
                    "version",
                    "3",
                    "enabled",
                    "false",
                    "operation",
                    "UPDATED",
                    "resource",
                    "api:test",
                    "action",
                    "read"),
                Instant.now()));
    assertEquals(BlockPolicyEffect.Kind.POLICY, policy.kind());
    var failed =
        BlockPolicyEffect.from(
            event(
                "auth.login.failed",
                "login",
                Map.of("attemptId", id, "reason", "CREDENTIALS_OR_ACCOUNT_REJECTED"),
                Instant.now()));
    assertEquals("", failed.subject());
    assertEquals("", failed.network());
    var risk =
        BlockPolicyEffect.from(
            event(
                "auth.login.suspicious",
                "auth:login",
                Map.of(
                    "userId",
                    id,
                    "version",
                    "1",
                    "sid",
                    id,
                    "riskScore",
                    "80",
                    "reasons",
                    "DEVICE_UNKNOWN"),
                Instant.now()));
    assertEquals(BlockPolicyEffect.Kind.RISK, risk.kind());
    assertFalse(risk.toString().contains(id));
    assertThrows(
        IllegalArgumentException.class,
        () -> BlockPolicyEffect.from(TestEvents.session(UUID.randomUUID())));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            BlockPolicyEffect.from(
                event(
                    "identity.user.created",
                    "invalid",
                    Map.of("status", "ACTIVE", "version", "0"),
                    Instant.now())));
  }

  @Test
  void ackRequiresAppliedOrDuplicateAndTransientStoreFailureRetries() throws Exception {
    var json = new ObjectMapper().findAndRegisterModules();
    var event =
        event(
            "identity.user.created",
            UUID.randomUUID().toString(),
            Map.of("status", "ACTIVE", "version", "0"),
            Instant.now());
    for (var result : BlockPolicyStore.Result.values()) {
      var handler =
          new BlockPolicyMessageHandler(json, new BlockPolicyHandler((effect, hash) -> result));
      assertEquals(
          result == BlockPolicyStore.Result.CONFLICT ? Result.REJECT : Result.ACK,
          handler.handle(TestEvents.properties(event), json.writeValueAsBytes(event)));
    }
    var unavailable =
        new BlockPolicyMessageHandler(
            json,
            new BlockPolicyHandler(
                (effect, hash) -> {
                  throw new IllegalStateException("synthetic");
                }));
    assertEquals(
        Result.RETRY,
        unavailable.handle(TestEvents.properties(event), json.writeValueAsBytes(event)));
    var missing =
        new BlockPolicyMessageHandler(json, new BlockPolicyHandler((effect, hash) -> null));
    assertEquals(
        Result.RETRY, missing.handle(TestEvents.properties(event), json.writeValueAsBytes(event)));
  }

  @Test
  void invalidMessagesNeverReachStore() throws Exception {
    var calls = new AtomicInteger();
    var json = new ObjectMapper().findAndRegisterModules();
    var handler =
        new BlockPolicyMessageHandler(
            json,
            new BlockPolicyHandler(
                (effect, hash) -> {
                  calls.incrementAndGet();
                  return BlockPolicyStore.Result.APPLIED;
                }));
    var event =
        event(
            "identity.user.created",
            UUID.randomUUID().toString(),
            Map.of("status", "ACTIVE", "version", "0"),
            Instant.now());
    byte[] body = json.writeValueAsBytes(event);
    var properties = TestEvents.properties(event);
    assertEquals(Result.REJECT, handler.handle(null, body));
    assertEquals(Result.REJECT, handler.handle(properties, new byte[65537]));
    assertEquals(
        Result.REJECT,
        handler.handle(properties, "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    assertEquals(
        Result.REJECT, handler.handle(properties.builder().messageId("mismatch").build(), body));
    var foreign =
        new SecurityEvent(
            event.eventId(),
            event.eventType(),
            1,
            event.occurredAt(),
            "foreign",
            event.correlationId(),
            event.actor(),
            event.context(),
            event.data());
    assertEquals(
        Result.REJECT,
        handler.handle(TestEvents.properties(foreign), json.writeValueAsBytes(foreign)));
    assertEquals(0, calls.get());
  }

  @Test
  void boundedConfigurationAndIdentityContractRejectInvalidValues() {
    assertDoesNotThrow(() -> new BlockPolicyProperties(300, 300, 300, 5, 20));
    assertThrows(
        IllegalArgumentException.class, () -> new BlockPolicyProperties(0, 300, 300, 5, 20));
    assertThrows(
        IllegalArgumentException.class, () -> new BlockPolicyProperties(300, 3601, 300, 5, 20));
    assertThrows(
        IllegalArgumentException.class, () -> new BlockPolicyProperties(300, 300, 0, 5, 20));
    assertThrows(
        IllegalArgumentException.class, () -> new BlockPolicyProperties(300, 300, 300, 1001, 20));
    assertThrows(
        IllegalArgumentException.class, () -> new BlockPolicyProperties(300, 300, 300, 5, 0));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            event(
                "identity.user.updated",
                UUID.randomUUID().toString(),
                Map.of("status", "ACTIVE"),
                Instant.now()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new BlockPolicyEffect(
                UUID.randomUUID(),
                BlockPolicyEffect.Kind.IDENTITY,
                UUID.randomUUID().toString(),
                "0",
                "UNKNOWN",
                "",
                "",
                0));
  }
}

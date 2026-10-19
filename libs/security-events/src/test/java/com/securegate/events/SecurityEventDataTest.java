package com.securegate.events;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SecurityEventDataTest {
  static Map<String, String> data(String type) {
    String id = UUID.randomUUID().toString();
    return switch (type) {
      case "policy.changed" ->
          Map.of(
              "policyId",
              id,
              "version",
              "4",
              "enabled",
              "true",
              "operation",
              "UPDATED",
              "resource",
              "api:test",
              "action",
              "read");
      case "user.locked" ->
          Map.of("userId", id, "version", "4", "status", "LOCKED", "reason", "ADMINISTRATIVE");
      case "session.terminated" ->
          Map.of("userId", id, "version", "4", "sid", id, "reason", "BROWSER_CLOSED");
      case "auth.login.failed" ->
          Map.of("attemptId", id, "reason", "CREDENTIALS_OR_ACCOUNT_REJECTED");
      case "auth.login.suspicious" ->
          Map.of(
              "userId",
              id,
              "version",
              "4",
              "sid",
              id,
              "riskScore",
              "80",
              "reasons",
              "DEVICE_UNKNOWN");
      case "token.revoked" ->
          Map.of(
              "authorizationId",
              id,
              "registeredClientId",
              id,
              "target",
              "ACCESS_TOKEN",
              "jti",
              id,
              "reason",
              "CLIENT_REVOCATION",
              "expiresAt",
              Instant.now().plusSeconds(300).toString());
      default -> Map.of("version", "4");
    };
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "policy.changed",
        "user.locked",
        "session.terminated",
        "auth.login.failed",
        "auth.login.suspicious",
        "token.revoked"
      })
  void validatesEachProducerAndRejectsIncompleteFacts(String type) throws Exception {
    assertDoesNotThrow(() -> SecurityEventData.validate(type, data(type)));
    try (var resource =
        getClass().getResourceAsStream("/contracts/security-integration-event-v1.schema.json")) {
      assertNotNull(resource);
      var schema = new com.fasterxml.jackson.databind.ObjectMapper().readTree(resource);
      assertEquals(
          "urn:securegate:security-event:1", schema.path("allOf").get(0).path("$ref").asText());
      boolean found = false;
      for (var rule : schema.path("allOf")) {
        if (type.equals(
            rule.path("if").path("properties").path("eventType").path("const").asText())) {
          found = true;
          for (var required : rule.path("then").path("properties").path("data").path("required")) {
            assertTrue(data(type).containsKey(required.asText()));
          }
        }
      }
      assertTrue(found);
    }
    for (String key : data(type).keySet()) {
      var incomplete = new HashMap<>(data(type));
      incomplete.remove(key);
      assertThrows(
          IllegalArgumentException.class, () -> SecurityEventData.validate(type, incomplete), key);
    }
  }

  @Test
  void limitsObservedAddressesToNumericLiteralsAndRejectsExtraCredentialFields() {
    assertEquals("127.0.0.1", SecurityEventData.numericAddress("127.0.0.1"));
    assertNotNull(SecurityEventData.numericAddress("2001:db8::1"));
    for (String invalid :
        new String[] {
          "example.test", "localhost", "127.1", "999.0.0.1", "01.0.0.1", "fe80::1%eth0"
        }) {
      assertThrows(IllegalArgumentException.class, () -> SecurityEventData.numericAddress(invalid));
    }
    var failed = new HashMap<>(data("auth.login.failed"));
    failed.put("password", "synthetic");
    assertThrows(
        IllegalArgumentException.class,
        () -> SecurityEventData.validate("auth.login.failed", failed));
    failed.remove("password");
    failed.put("userId", UUID.randomUUID().toString());
    assertThrows(
        IllegalArgumentException.class,
        () -> SecurityEventData.validate("auth.login.failed", failed));
  }

  @Test
  void rejectsInvalidRiskVersionAndTargetWhileAllowingFamilyAndUnknownAccountFacts() {
    var risk = new HashMap<>(data("auth.login.suspicious"));
    risk.put("riskScore", "69");
    assertThrows(
        IllegalArgumentException.class,
        () -> SecurityEventData.validate("auth.login.suspicious", risk));
    risk.put("riskScore", "101");
    assertThrows(
        IllegalArgumentException.class,
        () -> SecurityEventData.validate("auth.login.suspicious", risk));
    var locked = new HashMap<>(data("user.locked"));
    locked.put("version", "-1");
    assertThrows(
        IllegalArgumentException.class, () -> SecurityEventData.validate("user.locked", locked));
    var revoked = new HashMap<>(data("token.revoked"));
    revoked.put("target", "AUTHORIZATION");
    revoked.remove("jti");
    assertDoesNotThrow(() -> SecurityEventData.validate("token.revoked", revoked));
    revoked.put("target", "SESSION");
    assertThrows(
        IllegalArgumentException.class, () -> SecurityEventData.validate("token.revoked", revoked));
  }
}

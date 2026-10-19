package com.securegate.events;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SecurityEventTest {
  private static final ObjectMapper JSON =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  private final UUID id = UUID.randomUUID();
  private final Instant now = Instant.parse("2026-10-19T12:00:00.123456Z");

  private SecurityEvent event(
      String type, int version, Map<String, String> context, Map<String, String> data) {
    return new SecurityEvent(
        id, type, version, now, "iam-core", "contract-test", "actor", context, data);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "auth.login.suspicious",
        "auth.login.failed",
        "token.revoked",
        "session.terminated",
        "policy.changed",
        "user.locked",
        "identity.user.created"
      })
  void roundTripsCanonicalHeadersAndUtcTimeWithoutSpring(String type) throws Exception {
    var original = event(type, 1, Map.of("resource", "synthetic-resource"), Map.of("version", "4"));
    var payload = JSON.writeValueAsString(original);
    var tree = JSON.readTree(payload);
    assertEquals(type, tree.path("eventType").asText());
    assertEquals(1, tree.path("schemaVersion").asInt());
    assertEquals(now.toString(), tree.path("occurredAt").asText());
    assertEquals(id.toString(), tree.path("eventId").asText());
    assertEquals(9, tree.size());
    assertEquals(original, JSON.readValue(payload, SecurityEvent.class));
    try (var schema = getClass().getResourceAsStream("/contracts/security-event-v1.schema.json")) {
      assertNotNull(schema);
      var definition = JSON.readTree(schema);
      Set<String> fields = new java.util.HashSet<>();
      tree.fieldNames().forEachRemaining(fields::add);
      Set<String> required = new java.util.HashSet<>();
      definition.path("required").forEach(field -> required.add(field.asText()));
      assertEquals(required, fields);
      assertEquals(1, definition.path("properties").path("schemaVersion").path("const").asInt());
    }
  }

  @Test
  void rejectsUnknownVersionsMalformedHeadersAndUnsupportedMetadata() throws Exception {
    assertThrows(
        IllegalArgumentException.class,
        () -> event("policy.changed", 2, Map.of("resource", "x"), Map.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> event("policy.*", 1, Map.of("resource", "x"), Map.of()));
    assertThrows(
        IllegalArgumentException.class, () -> event("policy.changed", 1, Map.of(), Map.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> event("policy.changed", 1, Map.of("resource", "x".repeat(101)), Map.of()));
    assertThrows(IllegalArgumentException.class, () -> event("policy.changed", 1, null, Map.of()));
    for (String value : new String[] {"", " ", "line\nbreak", "x".repeat(4097)}) {
      assertThrows(
          IllegalArgumentException.class,
          () -> event("policy.changed", 1, Map.of("resource", "x"), Map.of("version", value)));
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> event("policy.changed", 1, Map.of("resource", "x"), Map.of("bad-key", "value")));
    var payload =
        JSON.writeValueAsString(event("policy.changed", 1, Map.of("resource", "x"), Map.of()));
    assertThrows(
        com.fasterxml.jackson.core.JsonProcessingException.class,
        () ->
            JSON.readValue(
                payload.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
                SecurityEvent.class));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SecurityEvent(
                null,
                "policy.changed",
                1,
                now,
                "iam-core",
                "correlation",
                "actor",
                Map.of("resource", "x"),
                Map.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SecurityEvent(
                id,
                "policy.changed",
                1,
                now,
                "Invalid Producer",
                "correlation",
                "actor",
                Map.of("resource", "x"),
                Map.of()));
  }

  @Test
  void boundsHeaderAndMetadataSizesAndRejectsInvalidTimes() {
    var context = Map.of("resource", "x");
    for (Instant invalid : new Instant[] {null, Instant.EPOCH.minusSeconds(1)}) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new SecurityEvent(
                  id,
                  "policy.changed",
                  1,
                  invalid,
                  "iam-core",
                  "test",
                  "actor",
                  context,
                  Map.of()));
    }
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SecurityEvent(
                id, "policy.changed", 1, now, "p".repeat(65), "test", "actor", context, Map.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SecurityEvent(
                id,
                "policy.changed",
                1,
                now,
                "iam-core",
                "c".repeat(65),
                "actor",
                context,
                Map.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SecurityEvent(
                id,
                "policy.changed",
                1,
                now,
                "iam-core",
                "test",
                "a".repeat(201),
                context,
                Map.of()));
    var data = new HashMap<String, String>();
    for (int i = 0; i < 33; i++) {
      data.put("key" + i, "value");
    }
    assertThrows(IllegalArgumentException.class, () -> event("policy.changed", 1, context, data));
    data.clear();
    data.put("key", null);
    assertThrows(IllegalArgumentException.class, () -> event("policy.changed", 1, context, data));
  }

  @Test
  void copiesMetadataAndRedactsDiagnosticOutput() {
    var data = new HashMap<String, String>();
    data.put("version", "private-metadata");
    var event = event("policy.changed", 1, Map.of("resource", "private-resource"), data);
    data.put("version", "different");
    assertEquals("private-metadata", event.data().get("version"));
    assertThrows(
        UnsupportedOperationException.class, () -> event.data().put("version", "different"));
    assertFalse(event.toString().contains("private-metadata"));
    assertFalse(event.toString().contains("private-resource"));
    assertTrue(event.toString().contains(id.toString()));
  }
}

package com.securegate.enforcer;

import com.rabbitmq.client.AMQP;
import com.securegate.events.SecurityEvent;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class TestEvents {
  private TestEvents() {}

  public static SecurityEvent token(UUID id, String target, String value, Instant expires) {
    var data = new HashMap<String, String>();
    data.put("authorizationId", value);
    data.put("registeredClientId", "test-client");
    data.put("target", target);
    data.put("reason", "CLIENT_REVOCATION");
    data.put("expiresAt", expires.toString());
    if (target.equals("ACCESS_TOKEN")) {
      data.put("jti", value);
    }
    return new SecurityEvent(
        id,
        "token.revoked",
        1,
        Instant.now(),
        "iam-core",
        "test-correlation",
        "synthetic",
        Map.of("resource", "test"),
        data);
  }

  public static SecurityEvent session(UUID sid) {
    return new SecurityEvent(
        UUID.randomUUID(),
        "session.terminated",
        1,
        Instant.now(),
        "iam-core",
        "test-correlation",
        "synthetic",
        Map.of("resource", "test"),
        Map.of(
            "userId",
            UUID.randomUUID().toString(),
            "sid",
            sid.toString(),
            "version",
            "0",
            "reason",
            "BROWSER_CLOSED"));
  }

  public static AMQP.BasicProperties properties(SecurityEvent event) {
    return new AMQP.BasicProperties.Builder()
        .messageId(event.eventId().toString())
        .type(event.eventType())
        .appId(event.producer())
        .correlationId(event.correlationId())
        .contentType("application/json")
        .contentEncoding("UTF-8")
        .deliveryMode(2)
        .headers(Map.of("schemaVersion", 1))
        .build();
  }
}

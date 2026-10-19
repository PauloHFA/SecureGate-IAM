package com.securegate.enforcer.infrastructure;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.rabbitmq.client.AMQP;
import com.securegate.enforcer.application.RevocationHandler;
import com.securegate.enforcer.application.port.RevocationStore;
import com.securegate.enforcer.domain.Revocation;
import com.securegate.events.SecurityEvent;
import java.io.IOException;

public final class RevocationMessageHandler {
  public enum Result {
    ACK,
    REJECT,
    RETRY
  }

  private final ObjectReader reader;
  private final RevocationHandler handler;

  public RevocationMessageHandler(ObjectMapper json, RevocationHandler handler) {
    this.reader =
        json.readerFor(SecurityEvent.class)
            .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    this.handler = handler;
  }

  public Result handle(AMQP.BasicProperties properties, byte[] body) {
    Revocation revocation;
    try {
      if (body == null
          || body.length == 0
          || body.length > 65536
          || properties == null
          || !"application/json".equals(properties.getContentType())
          || !"UTF-8".equals(properties.getContentEncoding())
          || !Integer.valueOf(2).equals(properties.getDeliveryMode())) {
        return Result.REJECT;
      }
      SecurityEvent event = reader.readValue(body);
      Object schema =
          properties.getHeaders() == null ? null : properties.getHeaders().get("schemaVersion");
      if (!event.eventId().toString().equals(properties.getMessageId())
          || !event.eventType().equals(properties.getType())
          || !event.producer().equals(properties.getAppId())
          || !event.correlationId().equals(properties.getCorrelationId())
          || !Integer.valueOf(event.schemaVersion()).equals(schema)) {
        return Result.REJECT;
      }
      revocation = Revocation.from(event);
    } catch (IOException | RuntimeException invalid) {
      return Result.REJECT;
    }
    try {
      var result = handler.apply(revocation, RedisRevocationStore.fingerprint(body));
      if (result == null) {
        return Result.RETRY;
      }
      return result == RevocationStore.Result.CONFLICT ? Result.REJECT : Result.ACK;
    } catch (RuntimeException unavailable) {
      return Result.RETRY;
    }
  }
}

package com.securegate.enforcer.infrastructure;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.rabbitmq.client.AMQP;
import com.securegate.enforcer.application.BlockPolicyHandler;
import com.securegate.enforcer.application.port.BlockPolicyStore;
import com.securegate.enforcer.domain.BlockPolicyEffect;
import com.securegate.enforcer.infrastructure.RevocationMessageHandler.Result;
import com.securegate.events.SecurityEvent;
import java.io.IOException;

public final class BlockPolicyMessageHandler {
  private final ObjectReader reader;
  private final BlockPolicyHandler handler;

  public BlockPolicyMessageHandler(ObjectMapper json, BlockPolicyHandler handler) {
    reader =
        json.readerFor(SecurityEvent.class)
            .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    this.handler = handler;
  }

  public Result handle(AMQP.BasicProperties properties, byte[] body) {
    BlockPolicyEffect effect;
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
      effect = BlockPolicyEffect.from(event);
    } catch (IOException | RuntimeException invalid) {
      return Result.REJECT;
    }
    try {
      var result = handler.apply(effect, RedisRevocationStore.fingerprint(body));
      return result == null
          ? Result.RETRY
          : result == BlockPolicyStore.Result.CONFLICT ? Result.REJECT : Result.ACK;
    } catch (RuntimeException unavailable) {
      return Result.RETRY;
    }
  }
}

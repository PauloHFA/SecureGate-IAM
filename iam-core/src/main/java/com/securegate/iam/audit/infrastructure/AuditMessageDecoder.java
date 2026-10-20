package com.securegate.iam.audit.infrastructure;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.rabbitmq.client.AMQP;
import com.securegate.events.SecurityEvent;
import java.io.IOException;

/** Validates the versioned envelope before any database write. Never logs message contents. */
public final class AuditMessageDecoder {
  private final ObjectReader reader;
  private final com.fasterxml.jackson.databind.ObjectWriter writer;

  public AuditMessageDecoder(ObjectMapper json) {
    writer =
        json.writerFor(SecurityEvent.class)
            .without(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    reader =
        json.readerFor(SecurityEvent.class)
            .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
  }

  public SecurityEvent decode(AMQP.BasicProperties properties, byte[] body) {
    try {
      if (body == null
          || body.length == 0
          || body.length > 65536
          || properties == null
          || !"application/json".equals(properties.getContentType())
          || !"UTF-8".equals(properties.getContentEncoding())
          || !Integer.valueOf(2).equals(properties.getDeliveryMode())) {
        throw new IllegalArgumentException();
      }
      SecurityEvent event = reader.readValue(body);
      Object schema =
          properties.getHeaders() == null ? null : properties.getHeaders().get("schemaVersion");
      if (event == null
          || !event.eventId().toString().equals(properties.getMessageId())
          || !event.eventType().equals(properties.getType())
          || !event.producer().equals(properties.getAppId())
          || !event.correlationId().equals(properties.getCorrelationId())
          || !Integer.valueOf(event.schemaVersion()).equals(schema)
          || properties.getTimestamp() == null
          || properties.getTimestamp().getTime() / 1000 != event.occurredAt().getEpochSecond()) {
        throw new IllegalArgumentException();
      }
      // JSONB inserts separator whitespace. Reserve room below the database's 65536-byte cap.
      if (writer.writeValueAsBytes(event).length > 65000) {
        throw new IllegalArgumentException();
      }
      return event;
    } catch (IOException | RuntimeException invalid) {
      throw new IllegalArgumentException("Invalid audit message.");
    }
  }
}

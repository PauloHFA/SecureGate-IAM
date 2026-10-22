package com.securegate.enforcer.bootstrap;

import ch.qos.logback.classic.spi.ILoggingEvent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.logging.structured.StructuredLogFormatter;

/** A deliberately restricted operational schema, independent of arbitrary event text. */
public final class SafeOperationalLogFormatter implements StructuredLogFormatter<ILoggingEvent> {
  private static final Map<String, String> EVENTS =
      Map.ofEntries(
          Map.entry(
              "Security event consumer unavailable (queue={}, type={})",
              "security.consumer.unavailable"));
  private final JsonWriter<ILoggingEvent> writer =
      JsonWriter.of(
          members -> {
            members.add("timestamp", event -> event.getInstant().toString());
            members.add("level", event -> event.getLevel().toString());
            members.add("service", event -> "policy-enforcer");
            members.add(
                "event",
                event ->
                    event.getMessage() == null
                        ? "log.record"
                        : EVENTS.getOrDefault(event.getMessage(), "log.record"));
            members.add("correlationHash", SafeOperationalLogFormatter::correlationHash);
            members.add("traceId", event -> traceField(event, "traceId", 32));
            members.add("spanId", event -> traceField(event, "spanId", 16));
          });

  @Override
  public String format(ILoggingEvent event) {
    return writer.writeToString(event) + "\n";
  }

  private static String traceField(ILoggingEvent event, String key, int length) {
    String value = event.getMDCPropertyMap().get(key);
    return value != null
            && value.matches("[0-9a-f]{" + length + "}")
            && !value.equals("0".repeat(length))
        ? value
        : "";
  }

  private static String correlationHash(ILoggingEvent event) {
    String id = event.getMDCPropertyMap().get("correlationId");
    if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
      return "";
    }
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable");
    }
  }
}

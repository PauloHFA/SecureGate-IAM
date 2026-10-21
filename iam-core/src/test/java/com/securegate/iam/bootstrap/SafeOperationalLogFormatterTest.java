package com.securegate.iam.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SafeOperationalLogFormatterTest {
  private final SafeOperationalLogFormatter formatter = new SafeOperationalLogFormatter();
  private final ObjectMapper json = new ObjectMapper();

  @Test
  void onlyExportsBoundedNonzeroHexadecimalTraceIdentifiers() throws Exception {
    var context = new LoggerContext();
    try {
      var event =
          new LoggingEvent(
              "synthetic", context.getLogger("worker"), Level.INFO, "test", null, null);
      var mdc = new java.util.HashMap<String, String>();
      mdc.put("traceId", "1".repeat(32));
      mdc.put("spanId", "2".repeat(16));
      event.setMDCPropertyMap(mdc);
      var tree = json.readTree(formatter.format(event));
      assertThat(tree.get("traceId").asText()).isEqualTo(mdc.get("traceId"));
      assertThat(tree.get("spanId").asText()).isEqualTo(mdc.get("spanId"));
      for (String invalid : java.util.List.of("0".repeat(32), "SECRET\r\n", "x".repeat(2000))) {
        mdc.put("traceId", invalid);
        mdc.put("spanId", invalid);
        tree = json.readTree(formatter.format(event));
        assertThat(tree.get("traceId").asText()).isEmpty();
        assertThat(tree.get("spanId").asText()).isEmpty();
      }
    } finally {
      context.stop();
    }
  }

  @Test
  void handlesNullMessageWithoutBreakingLogging() throws Exception {
    var context = new LoggerContext();
    try {
      var event =
          new LoggingEvent("synthetic", context.getLogger("worker"), Level.INFO, null, null, null);
      event.setMDCPropertyMap(Map.of());
      assertThat(json.readTree(formatter.format(event)).get("event").asText())
          .isEqualTo("log.record");
    } finally {
      context.stop();
    }
  }

  @Test
  void excludesSecretsFromMessagesArgumentsExceptionsMdcAndKeyValuePairs() throws Exception {
    var context = new LoggerContext();
    try {
      var event =
          new LoggingEvent(
              "synthetic",
              context.getLogger("secret-logger"),
              Level.ERROR,
              "Authorization Bearer {}\r\npassword=synthetic-password",
              new RuntimeException("synthetic-exception-token"),
              new Object[] {"synthetic-argument-token"});
      event.setMDCPropertyMap(
          Map.of("password", "synthetic-mdc-token", "correlationId", "unsafe\r\nvalue"));
      event.addKeyValuePair(new org.slf4j.event.KeyValuePair("token", "synthetic-kv-token"));
      String line = formatter.format(event);
      assertThat(line.lines().count()).isEqualTo(1);
      var tree = json.readTree(line);
      assertThat(tree.size()).isEqualTo(7);
      assertThat(tree.get("service").asText()).isEqualTo("iam-core");
      assertThat(tree.get("event").asText()).isEqualTo("log.record");
      assertThat(tree.get("correlationHash").asText()).isEmpty();
      assertThat(line)
          .doesNotContain("synthetic", "secret-logger", "Authorization", "password", "stack_trace");
    } finally {
      context.stop();
    }
  }

  @Test
  void classifiesKnownEventAndHashesBoundedCorrelationWithoutExportingArguments() throws Exception {
    var context = new LoggerContext();
    try {
      var event =
          new LoggingEvent(
              "synthetic",
              context.getLogger("worker"),
              Level.WARN,
              "Unhandled request failure (type={})",
              null,
              new Object[] {"synthetic-secret", "synthetic-secret"});
      var mdc = new java.util.HashMap<String, String>();
      mdc.put("correlationId", "abc");
      event.setMDCPropertyMap(mdc);
      var tree = json.readTree(formatter.format(event));
      assertThat(tree.get("event").asText()).isEqualTo("request.failure");
      // Public SHA-256 test vector for UTF-8 abc, not a credential.
      assertThat(tree.get("correlationHash").asText())
          .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
      assertThat(formatter.format(event)).doesNotContain("synthetic-secret", "abc");
      mdc.put("correlationId", "x".repeat(65));
      assertThat(json.readTree(formatter.format(event)).get("correlationHash").asText()).isEmpty();
    } finally {
      context.stop();
    }
  }
}

package com.securegate.enforcer.infrastructure;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.LongString;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.io.IOException;
import java.util.Locale;
import java.util.regex.Pattern;
import org.slf4j.MDC;

/** Strict W3C parent extraction; no payload, free-form errors or baggage in telemetry. */
public final class ConsumerTrace {
  private static final Pattern PARENT = Pattern.compile("00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]");
  private final Tracer tracer;

  public ConsumerTrace(Tracer tracer) {
    this.tracer = tracer;
  }

  @FunctionalInterface
  public interface DeliveryAction {
    RevocationMessageHandler.Result run() throws IOException;
  }

  public RevocationMessageHandler.Result consume(
      AMQP.BasicProperties properties,
      boolean redelivered,
      boolean blockPolicy,
      DeliveryAction action)
      throws IOException {
    if (tracer == null) return action.run();
    var builder = tracer.spanBuilder().name("security.consume").kind(Span.Kind.CONSUMER);
    String parent = parent(properties);
    if (parent == null) builder.setNoParent();
    else
      builder.setParent(
          tracer
              .traceContextBuilder()
              .traceId(parent.substring(3, 35))
              .spanId(parent.substring(36, 52))
              .sampled(parent.endsWith("01"))
              .build());
    var span = builder.start();
    String outcome = "unavailable";
    String previousTrace = MDC.get("traceId");
    String previousSpan = MDC.get("spanId");
    try (var scope = tracer.withSpan(span)) {
      MDC.put("traceId", span.context().traceId());
      MDC.put("spanId", span.context().spanId());
      var result = action.run();
      outcome = result.name().toLowerCase(Locale.ROOT);
      return result;
    } finally {
      restore("traceId", previousTrace);
      restore("spanId", previousSpan);
      span.tag("messaging.system", "rabbitmq");
      span.tag("messaging.consumer", blockPolicy ? "block_policy" : "revocation");
      span.tag("messaging.redelivered", redelivered ? "true" : "false");
      span.tag("messaging.outcome", outcome);
      span.end();
    }
  }

  private static void restore(String key, String previous) {
    if (previous == null) MDC.remove(key);
    else MDC.put(key, previous);
  }

  private static String parent(AMQP.BasicProperties properties) {
    if (properties == null || properties.getHeaders() == null) return null;
    var headers = properties.getHeaders();
    // A map cannot repeat exact keys; ambiguous case variants are rejected as well.
    if (headers.keySet().stream().filter("traceparent"::equalsIgnoreCase).count() != 1) return null;
    Object raw = headers.get("traceparent");
    String value;
    if (raw instanceof String text) value = text;
    else if (raw instanceof LongString text && text.length() == 55) value = text.toString();
    else return null;
    if (!PARENT.matcher(value).matches()
        || value.substring(3, 35).equals("0".repeat(32))
        || value.substring(36, 52).equals("0".repeat(16))) return null;
    return value;
  }
}

package com.securegate.iam.audit.infrastructure;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.LongString;
import com.securegate.iam.audit.application.port.OutboxTraceParent;
import io.micrometer.tracing.Link;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import org.slf4j.MDC;

/** Batch roots link all valid producer contexts; isolated deliveries have explicit parents. */
public final class AuditConsumerTrace {
  public enum Outcome {
    STORED,
    ISOLATED,
    RETRY,
    INVALID,
    APPENDED,
    DUPLICATE,
    CONFLICT,
    LATE,
    UNAVAILABLE
  }

  public record Delivery(String parent, boolean redelivered) {
    public Delivery {
      parent = OutboxTraceParent.safeOrNull(parent);
    }

    @Override
    public String toString() {
      return "Delivery[context=restricted]";
    }
  }

  @FunctionalInterface
  public interface Action {
    Outcome run() throws IOException;
  }

  private final Tracer tracer;

  public AuditConsumerTrace(Tracer tracer) {
    this.tracer = tracer;
  }

  public Delivery delivery(AMQP.BasicProperties properties, boolean redelivered) {
    if (tracer == null || properties == null || properties.getHeaders() == null)
      return new Delivery(null, redelivered);
    var headers = properties.getHeaders();
    if (headers.keySet().stream().filter("traceparent"::equalsIgnoreCase).count() != 1)
      return new Delivery(null, redelivered);
    Object raw = headers.get("traceparent");
    String parent =
        raw instanceof String text
            ? text
            : raw instanceof LongString text && text.length() == 55 ? text.toString() : null;
    return new Delivery(parent, redelivered);
  }

  public Outcome batch(List<Delivery> deliveries, Action action) throws IOException {
    if (tracer == null) return action.run();
    var builder =
        tracer.spanBuilder().name("audit.consume.batch").kind(Span.Kind.CONSUMER).setNoParent();
    var seen = new HashSet<String>();
    for (var delivery : deliveries) {
      if (delivery.parent() != null && seen.add(delivery.parent().substring(0, 52)))
        builder.addLink(new Link(context(delivery.parent())));
    }
    return scoped(
        builder.start(), "batch", deliveries.stream().anyMatch(Delivery::redelivered), action);
  }

  public Outcome individual(Delivery delivery, boolean invalid, Action action) throws IOException {
    if (tracer == null) return action.run();
    var builder = tracer.spanBuilder().name("audit.consume.message").kind(Span.Kind.CONSUMER);
    if (delivery.parent() == null) builder.setNoParent();
    else builder.setParent(context(delivery.parent()));
    return scoped(
        builder.start(), invalid ? "invalid" : "isolated", delivery.redelivered(), action);
  }

  private io.micrometer.tracing.TraceContext context(String parent) {
    return tracer
        .traceContextBuilder()
        .traceId(parent.substring(3, 35))
        .spanId(parent.substring(36, 52))
        .sampled(parent.endsWith("01"))
        .build();
  }

  private Outcome scoped(Span span, String phase, boolean redelivered, Action action)
      throws IOException {
    String previousTrace = MDC.get("traceId");
    String previousSpan = MDC.get("spanId");
    Outcome outcome = Outcome.UNAVAILABLE;
    try (var scope = tracer.withSpan(span)) {
      MDC.put("traceId", span.context().traceId());
      MDC.put("spanId", span.context().spanId());
      outcome = java.util.Objects.requireNonNull(action.run());
      return outcome;
    } finally {
      restore("traceId", previousTrace);
      restore("spanId", previousSpan);
      span.tag("messaging.system", "rabbitmq");
      span.tag("messaging.consumer", "audit");
      span.tag("messaging.phase", phase);
      span.tag("messaging.redelivered", redelivered ? "true" : "false");
      span.tag("messaging.outcome", outcome.name().toLowerCase(Locale.ROOT));
      span.end();
    }
  }

  private static void restore(String key, String previous) {
    if (previous == null) MDC.remove(key);
    else MDC.put(key, previous);
  }
}

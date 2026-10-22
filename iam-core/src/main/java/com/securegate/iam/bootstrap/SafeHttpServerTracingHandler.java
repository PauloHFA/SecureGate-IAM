package com.securegate.iam.bootstrap;

import io.micrometer.observation.Observation;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.handler.PropagatingReceiverTracingObservationHandler;
import io.micrometer.tracing.propagation.Propagator;
import java.util.Set;
import org.springframework.http.server.observation.ServerRequestObservationContext;

/** Keeps HTTP tracing separate from metric conventions and their existing URI labels. */
public final class SafeHttpServerTracingHandler
    extends PropagatingReceiverTracingObservationHandler<ServerRequestObservationContext> {
  private static final Set<String> METHODS =
      Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "TRACE");
  private final Propagator propagator;

  public SafeHttpServerTracingHandler(Tracer tracer, Propagator propagator) {
    super(tracer, propagator);
    this.propagator = propagator;
  }

  @Override
  public boolean supportsContext(Observation.Context context) {
    return context instanceof ServerRequestObservationContext;
  }

  @Override
  public void onStart(ServerRequestObservationContext context) {
    // Deliberately omit remote service, address and observation attributes.
    getTracingContext(context)
        .setSpan(
            propagator
                .extract(
                    context.getCarrier(), (carrier, key) -> context.getGetter().get(carrier, key))
                .kind(Span.Kind.SERVER)
                .name("http.server")
                .start());
  }

  @Override
  public void onError(ServerRequestObservationContext context) {
    // Recording a Throwable would also record its message/stack trace in OpenTelemetry.
  }

  @Override
  public void onEvent(Observation.Event event, ServerRequestObservationContext context) {
    // Event names are not part of this bounded HTTP schema.
  }

  @Override
  public void onStop(ServerRequestObservationContext context) {
    Span span = getRequiredSpan(context);
    String method = context.getCarrier().getMethod();
    int status = context.getResponse() == null ? 0 : context.getResponse().getStatus();
    span.name("http.server");
    span.tag("http.method", METHODS.contains(method) ? method : "OTHER");
    span.tag("http.status_class", status >= 100 && status < 600 ? status / 100 + "xx" : "unknown");
    span.tag("http.failure", context.getError() != null || status >= 500 ? "true" : "false");
    endSpan(context, span);
  }
}

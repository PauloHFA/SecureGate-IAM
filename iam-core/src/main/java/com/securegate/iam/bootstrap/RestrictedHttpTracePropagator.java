package com.securegate.iam.bootstrap;

import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.context.propagation.TextMapSetter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/** Version 00 only; arbitrary baggage, tracestate and duplicate servlet headers are ignored. */
public final class RestrictedHttpTracePropagator implements TextMapPropagator {
  private static final Pattern PARENT = Pattern.compile("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}");
  private final TextMapPropagator delegate = W3CTraceContextPropagator.getInstance();

  @Override
  public Collection<String> fields() {
    return List.of("traceparent");
  }

  @Override
  public <C> void inject(Context context, C carrier, TextMapSetter<C> setter) {
    delegate.inject(
        context,
        carrier,
        (target, key, value) -> {
          if ("traceparent".equals(key)) {
            setter.set(target, key, value);
          }
        });
  }

  @Override
  public <C> Context extract(Context context, C carrier, TextMapGetter<C> getter) {
    String supplied = getter.get(carrier, "traceparent");
    if (carrier instanceof HttpServletRequest request
        && Collections.list(request.getHeaders("traceparent")).size() != 1) {
      return context;
    }
    if (supplied == null || !PARENT.matcher(supplied).matches()) {
      return context;
    }
    return delegate.extract(
        context,
        carrier,
        new TextMapGetter<C>() {
          @Override
          public Iterable<String> keys(C ignored) {
            return List.of("traceparent");
          }

          @Override
          public String get(C ignored, String key) {
            return "traceparent".equals(key) ? supplied : null;
          }
        });
  }
}

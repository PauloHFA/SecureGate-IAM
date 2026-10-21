package com.securegate.iam.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class RestrictedHttpTracePropagatorTest {
  private final RestrictedHttpTracePropagator propagator = new RestrictedHttpTracePropagator();
  private final TextMapGetter<MockHttpServletRequest> getter =
      new TextMapGetter<>() {
        public Iterable<String> keys(MockHttpServletRequest carrier) {
          return List.of("traceparent", "tracestate", "baggage");
        }

        public String get(MockHttpServletRequest carrier, String key) {
          return carrier.getHeader(key);
        }
      };

  private String valid() {
    return "00-" + "1".repeat(32) + "-" + "2".repeat(16) + "-01";
  }

  @Test
  void onlyAcceptsParentAndDropsBaggageAndTracestateOnExtractionAndInjection() {
    var request = new MockHttpServletRequest();
    request.addHeader("traceparent", valid());
    request.addHeader("tracestate", "vendor=synthetic-secret");
    request.addHeader("baggage", "token=synthetic-token");
    var context = propagator.extract(Context.root(), request, getter);
    var span = Span.fromContext(context).getSpanContext();
    assertThat(span.isValid()).isTrue();
    assertThat(span.getTraceState().isEmpty()).isTrue();
    Map<String, String> output = new HashMap<>();
    propagator.inject(context, output, Map::put);
    assertThat(output).containsOnlyKeys("traceparent");
  }

  @Test
  void rejectsDuplicatesMalformedZeroUppercaseOversizedAndUnsupportedVersion() {
    for (String supplied :
        List.of(
            "invalid",
            valid().replace("1", "A"),
            "00-" + "0".repeat(32) + "-" + "2".repeat(16) + "-01",
            valid().replace("00-", "01-"),
            valid() + "x",
            "x".repeat(2000))) {
      var request = new MockHttpServletRequest();
      request.addHeader("traceparent", supplied);
      assertThat(
              Span.fromContext(propagator.extract(Context.root(), request, getter))
                  .getSpanContext()
                  .isValid())
          .isFalse();
    }
    var request = new MockHttpServletRequest();
    request.addHeader("traceparent", valid());
    request.addHeader("traceparent", valid());
    assertThat(
            Span.fromContext(propagator.extract(Context.root(), request, getter))
                .getSpanContext()
                .isValid())
        .isFalse();
  }
}

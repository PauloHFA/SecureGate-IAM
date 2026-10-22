package com.securegate.iam.audit.infrastructure;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.impl.LongStringHelper;
import io.micrometer.tracing.otel.bridge.*;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.*;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import java.io.IOException;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class AuditConsumerTraceTest {
  static String parent(String digit) {
    return "00-" + digit.repeat(32) + "-" + digit.repeat(16) + "-01";
  }

  static AMQP.BasicProperties props(Object value) {
    return new AMQP.BasicProperties.Builder().headers(Map.of("traceparent", value)).build();
  }

  @Test
  void batchRootLinksDistinctProducersOnceAndRestoresAmbientScope() throws Exception {
    try (var fixture = new Fixture(Sampler.traceIdRatioBased(1))) {
      var ambient = fixture.tracer.nextSpan().name("worker").start();
      try (var scope = fixture.tracer.withSpan(ambient)) {
        MDC.put("traceId", "previous");
        var first = fixture.trace.delivery(props(parent("1")), false);
        var second =
            fixture.trace.delivery(props(LongStringHelper.asLongString(parent("2"))), true);
        fixture.trace.batch(
            List.of(first, second, first, new AuditConsumerTrace.Delivery(null, false)),
            () -> {
              assertThat(fixture.tracer.currentSpan().context().traceId())
                  .isNotIn("1".repeat(32), "2".repeat(32), ambient.context().traceId());
              assertThat(MDC.get("traceId"))
                  .isEqualTo(fixture.tracer.currentSpan().context().traceId());
              return AuditConsumerTrace.Outcome.STORED;
            });
        assertThat(fixture.tracer.currentSpan().context().spanId())
            .isEqualTo(ambient.context().spanId());
        assertThat(MDC.get("traceId")).isEqualTo("previous");
      } finally {
        ambient.end();
        MDC.remove("traceId");
      }
      var span =
          fixture.spans.stream()
              .filter(it -> it.getName().equals("audit.consume.batch"))
              .findFirst()
              .orElseThrow();
      assertThat(span.getParentSpanId()).isEqualTo("0".repeat(16));
      assertThat(span.getKind()).isEqualTo(io.opentelemetry.api.trace.SpanKind.CONSUMER);
      assertThat(span.getLinks()).hasSize(2);
      assertThat(span.getLinks().stream().map(it -> it.getSpanContext().getTraceId()))
          .containsExactly("1".repeat(32), "2".repeat(32));
      assertThat(span.getLinks())
          .allSatisfy(link -> assertThat(link.getAttributes().isEmpty()).isTrue());
      assertThat(span.getAttributes().size()).isEqualTo(5);
      assertThat(span.getEvents()).isEmpty();
      assertThat(span.getAttributes().get(AttributeKey.stringKey("messaging.redelivered")))
          .isEqualTo("true");
      assertThat(fixture.tracer.currentSpan()).isNull();
    }
  }

  @Test
  void hostileOrAbsentMetadataNeverBecomesLinkOrBusinessRejection() throws Exception {
    try (var fixture = new Fixture(Sampler.traceIdRatioBased(1))) {
      var values =
          List.of(
              "synthetic\r\nbaggage=secret",
              "x".repeat(1000),
              parent("a").toUpperCase(Locale.ROOT),
              parent("1").replace("1".repeat(32), "0".repeat(32)),
              parent("1").replace("1".repeat(16) + "-01", "0".repeat(16) + "-01"),
              LongStringHelper.asLongString("x".repeat(1000)),
              List.of(parent("1")),
              42);
      for (Object value : values)
        assertThat(fixture.trace.delivery(props(value), false).parent()).isNull();
      var ambiguous =
          new AMQP.BasicProperties.Builder()
              .headers(Map.of("traceparent", parent("1"), "Traceparent", parent("2")))
              .build();
      assertThat(fixture.trace.delivery(ambiguous, false).parent()).isNull();
      assertThat(fixture.trace.delivery(null, false).parent()).isNull();
      assertThat(fixture.trace.delivery(new AMQP.BasicProperties(), false).parent()).isNull();
      fixture.trace.batch(
          List.of(fixture.trace.delivery(props(values.get(0)), false)),
          () -> AuditConsumerTrace.Outcome.STORED);
      assertThat(fixture.spans.getFirst().getLinks()).isEmpty();
      assertThat(
              fixture
                  .spans
                  .getFirst()
                  .getAttributes()
                  .get(AttributeKey.stringKey("messaging.outcome")))
          .isEqualTo("stored");
    }
  }

  @Test
  void isolationUsesDeliveryParentThenRestoresBatchAndMdc() throws Exception {
    try (var fixture = new Fixture(Sampler.traceIdRatioBased(1))) {
      var delivery = fixture.trace.delivery(props(parent("1")), true);
      fixture.trace.batch(
          List.of(delivery),
          () -> {
            String batch = fixture.tracer.currentSpan().context().spanId();
            fixture.trace.individual(
                delivery,
                false,
                () -> {
                  assertThat(fixture.tracer.currentSpan().context().traceId())
                      .isEqualTo("1".repeat(32));
                  return AuditConsumerTrace.Outcome.CONFLICT;
                });
            assertThat(fixture.tracer.currentSpan().context().spanId()).isEqualTo(batch);
            assertThat(MDC.get("spanId")).isEqualTo(batch);
            return AuditConsumerTrace.Outcome.ISOLATED;
          });
      var message = fixture.spans.getFirst();
      assertThat(message.getParentSpanId()).isEqualTo("1".repeat(16));
      assertThat(message.getAttributes().get(AttributeKey.stringKey("messaging.phase")))
          .isEqualTo("isolated");
      assertThat(MDC.get("spanId")).isNull();
      assertThat(fixture.tracer.currentSpan()).isNull();
    }
  }

  @Test
  void exceptionsAndInvalidDispositionsCloseSpansWithoutFreeFormErrors() throws Exception {
    try (var fixture = new Fixture(Sampler.traceIdRatioBased(1))) {
      var delivery = fixture.trace.delivery(props(parent("1")), false);
      assertThatThrownBy(
              () ->
                  fixture.trace.batch(
                      List.of(delivery),
                      () -> {
                        throw new IOException("synthetic-credential");
                      }))
          .isInstanceOf(IOException.class);
      assertThatThrownBy(
              () ->
                  fixture.trace.individual(
                      delivery,
                      true,
                      () -> {
                        throw new IllegalStateException("synthetic-secret");
                      }))
          .isInstanceOf(IllegalStateException.class);
      fixture.trace.individual(delivery, true, () -> AuditConsumerTrace.Outcome.INVALID);
      assertThat(fixture.spans)
          .hasSize(3)
          .allSatisfy(
              span -> {
                assertThat(span.getEvents()).isEmpty();
                assertThat(span.getAttributes().size()).isEqualTo(5);
              });
      assertThat(
              fixture.spans.stream()
                  .map(it -> it.getAttributes().get(AttributeKey.stringKey("messaging.outcome"))))
          .containsExactly("unavailable", "unavailable", "invalid");
      assertThat(fixture.tracer.currentSpan()).isNull();
      assertThat(MDC.get("traceId")).isNull();
    }
  }

  @Test
  void zeroSamplingCannotBeOverriddenByProducerFlags() throws Exception {
    try (var fixture = new Fixture(Sampler.traceIdRatioBased(0))) {
      var delivery = fixture.trace.delivery(props(parent("1")), false);
      fixture.trace.batch(
          List.of(delivery),
          () -> {
            assertThat(fixture.tracer.currentSpan().context().sampled()).isFalse();
            return AuditConsumerTrace.Outcome.RETRY;
          });
      fixture.trace.individual(
          delivery,
          false,
          () -> {
            assertThat(fixture.tracer.currentSpan().context().sampled()).isFalse();
            return AuditConsumerTrace.Outcome.DUPLICATE;
          });
      assertThat(fixture.spans).isEmpty();
    }
  }

  @Test
  void disabledTracingDoesNotReadHeadersOrChangeOutcome() throws Exception {
    var trace = new AuditConsumerTrace(null);
    var props = mock(AMQP.BasicProperties.class);
    var delivery = trace.delivery(props, true);
    assertThat(trace.batch(List.of(delivery), () -> AuditConsumerTrace.Outcome.RETRY))
        .isEqualTo(AuditConsumerTrace.Outcome.RETRY);
    assertThat(trace.individual(delivery, true, () -> AuditConsumerTrace.Outcome.INVALID))
        .isEqualTo(AuditConsumerTrace.Outcome.INVALID);
    verifyNoInteractions(props);
  }

  static class Fixture implements AutoCloseable {
    final List<SpanData> spans = new ArrayList<>();
    final SdkTracerProvider provider;
    final OtelTracer tracer;
    final AuditConsumerTrace trace;

    Fixture(Sampler sampler) {
      provider =
          SdkTracerProvider.builder()
              .setSampler(sampler)
              .addSpanProcessor(
                  new SpanProcessor() {
                    public void onStart(Context ctx, ReadWriteSpan span) {}

                    public boolean isStartRequired() {
                      return false;
                    }

                    public void onEnd(ReadableSpan span) {
                      spans.add(span.toSpanData());
                    }

                    public boolean isEndRequired() {
                      return true;
                    }
                  })
              .build();
      tracer = new OtelTracer(provider.get("test"), new OtelCurrentTraceContext(), event -> {});
      trace = new AuditConsumerTrace(tracer);
    }

    public void close() {
      provider.close();
    }
  }
}

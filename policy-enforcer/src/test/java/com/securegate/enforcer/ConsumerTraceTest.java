package com.securegate.enforcer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.impl.LongStringHelper;
import com.securegate.enforcer.infrastructure.ConsumerTrace;
import com.securegate.enforcer.infrastructure.RevocationMessageHandler.Result;
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

class ConsumerTraceTest {
  static String parent() {
    return "00-" + "1".repeat(32) + "-" + "2".repeat(16) + "-01";
  }

  static AMQP.BasicProperties properties(Map<String, Object> headers) {
    return new AMQP.BasicProperties.Builder().headers(headers).build();
  }

  @Test
  void validStringAndRabbitLongStringCreateDistinctChildrenAndRestoreMdc() throws Exception {
    try (var tracing = new Tracing(Sampler.traceIdRatioBased(1))) {
      var ambient = tracing.tracer.nextSpan().name("ambient").start();
      try (var scope = tracing.tracer.withSpan(ambient)) {
        MDC.put("traceId", ambient.context().traceId());
        MDC.put("spanId", ambient.context().spanId());
        for (Object supplied : List.of(parent(), LongStringHelper.asLongString(parent()))) {
          assertThat(
                  tracing.consumer.consume(
                      properties(
                          Map.of(
                              "traceparent",
                              supplied,
                              "baggage",
                              "password=synthetic",
                              "tracestate",
                              "synthetic=secret")),
                      true,
                      false,
                      () -> {
                        assertThat(tracing.tracer.currentSpan().context().traceId())
                            .isEqualTo("1".repeat(32));
                        assertThat(MDC.get("traceId")).isEqualTo("1".repeat(32));
                        return Result.RETRY;
                      }))
              .isEqualTo(Result.RETRY);
          assertThat(tracing.tracer.currentSpan().context().spanId())
              .isEqualTo(ambient.context().spanId());
          assertThat(MDC.get("spanId")).isEqualTo(ambient.context().spanId());
        }
      } finally {
        ambient.end();
        MDC.remove("traceId");
        MDC.remove("spanId");
      }
      assertThat(tracing.tracer.currentSpan()).isNull();
      assertThat(MDC.get("traceId")).isNull();
      var spans = tracing.consumed();
      assertThat(spans).hasSize(2);
      assertThat(spans.get(0).getSpanId()).isNotEqualTo(spans.get(1).getSpanId());
      for (var span : spans) {
        assertThat(span.getParentSpanId()).isEqualTo("2".repeat(16));
        assertThat(span.getKind()).isEqualTo(io.opentelemetry.api.trace.SpanKind.CONSUMER);
        assertThat(span.getAttributes().size()).isEqualTo(4);
        assertThat(span.getEvents()).isEmpty();
        assertThat(span.getAttributes().get(AttributeKey.stringKey("messaging.outcome")))
            .isEqualTo("retry");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("messaging.redelivered")))
            .isEqualTo("true");
      }
    }
  }

  @Test
  void malformedAmbiguousOrAbsentContextCreatesRootsInsteadOfInheritingWorker() throws Exception {
    var cases = new ArrayList<AMQP.BasicProperties>();
    cases.add(null);
    cases.add(new AMQP.BasicProperties());
    for (Object raw :
        List.of(
            "x".repeat(1000),
            parent().replace("1".repeat(32), "a".repeat(32)).toUpperCase(Locale.ROOT),
            "00-" + "0".repeat(32) + "-" + "2".repeat(16) + "-01",
            "00-" + "1".repeat(32) + "-" + "0".repeat(16) + "-01",
            parent().substring(0, 53) + "ff",
            "synthetic\r\nbaggage=secret",
            List.of(parent()),
            parent().getBytes(java.nio.charset.StandardCharsets.UTF_8),
            123,
            LongStringHelper.asLongString("x".repeat(1000)))) {
      cases.add(properties(Map.of("traceparent", raw)));
    }
    cases.add(properties(Map.of("traceparent", parent(), "Traceparent", parent())));
    try (var tracing = new Tracing(Sampler.traceIdRatioBased(1))) {
      var ambient = tracing.tracer.nextSpan().name("ambient").start();
      try (var scope = tracing.tracer.withSpan(ambient)) {
        for (var props : cases) {
          tracing.consumer.consume(
              props,
              false,
              true,
              () -> {
                assertThat(tracing.tracer.currentSpan().context().traceId())
                    .isNotEqualTo(ambient.context().traceId());
                return Result.ACK;
              });
        }
      } finally {
        ambient.end();
      }
      assertThat(tracing.consumed()).hasSize(cases.size());
      assertThat(tracing.consumed())
          .allSatisfy(span -> assertThat(span.getParentSpanId()).isEqualTo("0".repeat(16)));
      assertThat(tracing.tracer.currentSpan()).isNull();
    }
  }

  @Test
  void exceptionsAndAllDeliveryOutcomesCloseScopeWithoutExportingErrors() throws Exception {
    try (var tracing = new Tracing(Sampler.traceIdRatioBased(1))) {
      for (var result : Result.values()) {
        assertThat(
                tracing.consumer.consume(
                    properties(Map.of("traceparent", parent())), false, true, () -> result))
            .isEqualTo(result);
      }
      assertThatThrownBy(
              () ->
                  tracing.consumer.consume(
                      null,
                      false,
                      false,
                      () -> {
                        throw new IOException("synthetic-credential");
                      }))
          .isInstanceOf(IOException.class);
      assertThatThrownBy(
              () ->
                  tracing.consumer.consume(
                      null,
                      false,
                      false,
                      () -> {
                        throw new IllegalStateException("synthetic-token");
                      }))
          .isInstanceOf(IllegalStateException.class);
      assertThat(tracing.tracer.currentSpan()).isNull();
      assertThat(MDC.get("spanId")).isNull();
      assertThat(tracing.consumed()).hasSize(5);
      assertThat(tracing.consumed())
          .allSatisfy(
              span -> {
                assertThat(span.getAttributes().size()).isEqualTo(4);
                assertThat(span.getEvents()).isEmpty();
                assertThat(span.getAttributes().get(AttributeKey.stringKey("messaging.system")))
                    .isEqualTo("rabbitmq");
              });
      assertThat(
              tracing.consumed().stream()
                  .map(
                      span ->
                          span.getAttributes().get(AttributeKey.stringKey("messaging.outcome"))))
          .containsExactly("ack", "reject", "retry", "unavailable", "unavailable");
    }
  }

  @Test
  void localZeroSamplingOverridesSampledRemoteParent() throws Exception {
    try (var tracing = new Tracing(Sampler.traceIdRatioBased(0))) {
      tracing.consumer.consume(
          properties(Map.of("traceparent", parent())),
          false,
          false,
          () -> {
            assertThat(tracing.tracer.currentSpan().context().traceId()).isEqualTo("1".repeat(32));
            assertThat(tracing.tracer.currentSpan().context().sampled()).isFalse();
            return Result.ACK;
          });
      assertThat(tracing.spans).isEmpty();
      assertThat(tracing.tracer.currentSpan()).isNull();
    }
  }

  @Test
  void disabledTracingDoesNotInspectHeadersAndPreservesActionFailure() throws Exception {
    var props = mock(AMQP.BasicProperties.class);
    var consumer = new ConsumerTrace(null);
    assertThat(consumer.consume(props, true, true, () -> Result.ACK)).isEqualTo(Result.ACK);
    assertThatThrownBy(
            () ->
                consumer.consume(
                    props,
                    false,
                    false,
                    () -> {
                      throw new IOException("synthetic");
                    }))
        .isInstanceOf(IOException.class);
    verifyNoInteractions(props);
  }

  static class Tracing implements AutoCloseable {
    final List<SpanData> spans = new java.util.concurrent.CopyOnWriteArrayList<>();
    final SdkTracerProvider provider;
    final OtelTracer tracer;
    final ConsumerTrace consumer;

    Tracing(Sampler sampler) {
      provider =
          SdkTracerProvider.builder()
              .setSampler(sampler)
              .addSpanProcessor(
                  new SpanProcessor() {
                    public void onStart(Context context, ReadWriteSpan span) {}

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
      var listener = new Slf4JEventListener();
      tracer =
          new OtelTracer(provider.get("test"), new OtelCurrentTraceContext(), listener::onEvent);
      consumer = new ConsumerTrace(tracer);
    }

    List<SpanData> consumed() {
      return spans.stream().filter(span -> span.getName().equals("security.consume")).toList();
    }

    public void close() {
      provider.close();
    }
  }
}

package com.securegate.enforcer.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeout;

import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class LocalOtlpExportConfigurationTest {
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(
              ConsumerTracingConfiguration.class, LocalOtlpExportConfiguration.class);

  @Test
  void noExportByDefaultEvenWithTracingAndUnusableEndpoint() {
    runner
        .withPropertyValues(
            "management.tracing.enabled=true", "securegate.tracing.export.endpoint=invalid")
        .run(
            ctx ->
                assertThat(ctx)
                    .hasSingleBean(SdkTracerProvider.class)
                    .doesNotHaveBean(SpanProcessor.class));
  }

  @Test
  void exportRequiresTracing() {
    runner
        .withPropertyValues("securegate.tracing.export.enabled=true")
        .run(ctx -> assertThat(ctx).hasFailed());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "https://127.0.0.1:14318/v1/traces",
        "http://localhost:14318/v1/traces",
        "http://example.com:14318/v1/traces",
        "http://0.0.0.0:14318/v1/traces",
        "http://127.0.0.1:80/v1/traces",
        "http://127.0.0.1:65536/v1/traces",
        "http://127.0.0.1/v1/traces",
        "http://user:password@127.0.0.1:14318/v1/traces",
        "http://127.0.0.1:14318/v1/traces?token=test",
        "http://127.0.0.1:14318/v1/traces#fragment",
        "http://127.0.0.1:14318/other",
        "not a URI"
      })
  void rejectsDestinationsOutsideLocalContract(String endpoint) {
    enabled(endpoint).run(ctx -> assertThat(ctx).hasFailed());
  }

  @Test
  void exportsSampledSpanResourceParentAndLinkOverProtobufHttpAndCloses() throws Exception {
    try (var receiver = new Receiver()) {
      enabled(receiver.endpoint())
          .run(
              ctx -> {
                assertThat(ctx).hasNotFailed().hasSingleBean(SpanProcessor.class);
                var provider = ctx.getBean(SdkTracerProvider.class);
                var parent =
                    SpanContext.createFromRemoteParent(
                        "1".repeat(32),
                        "2".repeat(16),
                        TraceFlags.getSampled(),
                        TraceState.getDefault());
                var link =
                    SpanContext.createFromRemoteParent(
                        "3".repeat(32),
                        "4".repeat(16),
                        TraceFlags.getSampled(),
                        TraceState.getDefault());
                var span =
                    provider
                        .get("securegate.export-test")
                        .spanBuilder("safe.synthetic")
                        .setSpanKind(SpanKind.CONSUMER)
                        .setParent(Context.root().with(Span.wrap(parent)))
                        .addLink(link)
                        .setAttribute("messaging.system", "rabbitmq")
                        .startSpan();
                span.end();
                var flushed = provider.forceFlush().join(5, TimeUnit.SECONDS);
                assertThat(flushed.isSuccess()).isTrue();
                assertThat(receiver.bodies).hasSize(1);
                var wire = receiver.bodies.getFirst();
                assertThat(new String(wire, StandardCharsets.ISO_8859_1))
                    .contains(
                        "service.name",
                        "policy-enforcer",
                        "safe.synthetic",
                        "messaging.system",
                        "rabbitmq");
                assertThat(wire).containsSubsequence(HexFormat.of().parseHex(parent.getTraceId()));
                assertThat(wire).containsSubsequence(HexFormat.of().parseHex(parent.getSpanId()));
                assertThat(wire).containsSubsequence(HexFormat.of().parseHex(link.getTraceId()));
                assertThat(receiver.contentTypes).containsExactly("application/x-protobuf");
                assertThat(receiver.methods).containsExactly("POST");
                assertTimeout(
                    Duration.ofSeconds(5),
                    () ->
                        assertThat(provider.shutdown().join(4, TimeUnit.SECONDS).isSuccess())
                            .isTrue());
                // ApplicationContext closure calls both provider and processor close again (SDK
                // idempotence).
              });
    }
  }

  @Test
  void receiverFailureDoesNotBlockProducerQueueIsBoundedAndLaterSpansRecover() throws Exception {
    try (var receiver = new Receiver()) {
      receiver.status.set(503);
      receiver.gate = new CountDownLatch(1);
      enabled(receiver.endpoint())
          .run(
              ctx -> {
                var provider = ctx.getBean(SdkTracerProvider.class);
                var tracer = provider.get("securegate.export-test");
                for (int i = 0; i < 128; i++) tracer.spanBuilder("safe.before").startSpan().end();
                assertThat(receiver.entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertTimeout(
                    Duration.ofSeconds(3),
                    () -> {
                      for (int i = 0; i < 2000; i++)
                        tracer.spanBuilder("safe.overload").startSpan().end();
                    });
                receiver.gate.countDown();
                provider.forceFlush().join(5, TimeUnit.SECONDS);
                // At most the active batch and 512 queued spans, no retry of HTTP 503 batches.
                assertThat(receiver.bodies.size()).isBetween(1, 5);
                int failedRequests = receiver.bodies.size();
                receiver.status.set(200);
                tracer.spanBuilder("safe.recovered").startSpan().end();
                assertThat(provider.forceFlush().join(5, TimeUnit.SECONDS).isSuccess()).isTrue();
                assertThat(receiver.bodies).hasSize(failedRequests + 1);
                assertThat(new String(receiver.bodies.getLast(), StandardCharsets.ISO_8859_1))
                    .contains("safe.recovered");
              });
    }
  }

  @Test
  void timeoutReleasesTheBatchWorkerWhenReceiverDoesNotRespond() throws Exception {
    try (var receiver = new Receiver()) {
      receiver.gate = new CountDownLatch(1);
      enabled(receiver.endpoint())
          .run(
              ctx -> {
                var provider = ctx.getBean(SdkTracerProvider.class);
                provider
                    .get("securegate.export-test")
                    .spanBuilder("safe.timeout")
                    .startSpan()
                    .end();
                assertTimeout(
                    Duration.ofSeconds(4),
                    () -> {
                      var flushed = provider.forceFlush().join(3, TimeUnit.SECONDS);
                      assertThat(flushed.isDone()).isTrue();
                    });
                assertThat(receiver.bodies).hasSize(1);
                // No successful-response/retry requirement: this batch is deliberately lost on
                // timeout.
              });
    }
  }

  @Test
  void zeroSamplingDoesNotExportEvenWithExporterEnabled() throws Exception {
    try (var receiver = new Receiver()) {
      enabled(receiver.endpoint())
          .withPropertyValues("management.tracing.sampling.probability=0")
          .run(
              ctx -> {
                var provider = ctx.getBean(SdkTracerProvider.class);
                var span =
                    provider
                        .get("securegate.export-test")
                        .spanBuilder("safe.unsampled")
                        .startSpan();
                assertThat(span.getSpanContext().isSampled()).isFalse();
                span.end();
                assertThat(provider.forceFlush().join(3, TimeUnit.SECONDS).isSuccess()).isTrue();
                assertThat(receiver.bodies).isEmpty();
              });
    }
  }

  @Test
  void doesNotFollowReceiverRedirects() throws Exception {
    try (var receiver = new Receiver();
        var redirected = new Receiver()) {
      receiver.status.set(307);
      receiver.location = redirected.endpoint();
      enabled(receiver.endpoint())
          .run(
              ctx -> {
                var provider = ctx.getBean(SdkTracerProvider.class);
                provider
                    .get("securegate.export-test")
                    .spanBuilder("safe.redirect")
                    .startSpan()
                    .end();
                provider.forceFlush().join(4, TimeUnit.SECONDS);
                assertThat(receiver.bodies).hasSize(1);
                assertThat(redirected.bodies).isEmpty();
              });
    }
  }

  private ApplicationContextRunner enabled(String endpoint) {
    return runner.withPropertyValues(
        "management.tracing.enabled=true",
        "management.tracing.sampling.probability=1",
        "securegate.tracing.export.enabled=true",
        "securegate.tracing.export.endpoint=" + endpoint);
  }

  private static final class Receiver implements AutoCloseable {
    final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    final CopyOnWriteArrayList<byte[]> bodies = new CopyOnWriteArrayList<>();
    final CopyOnWriteArrayList<String> contentTypes = new CopyOnWriteArrayList<>();
    final CopyOnWriteArrayList<String> methods = new CopyOnWriteArrayList<>();
    final AtomicInteger status = new AtomicInteger(200);
    final CountDownLatch entered = new CountDownLatch(1);
    volatile String location;
    volatile CountDownLatch gate = new CountDownLatch(0);

    Receiver() throws Exception {
      server.createContext(
          "/v1/traces",
          exchange -> {
            try (exchange) {
              bodies.add(exchange.getRequestBody().readAllBytes());
              contentTypes.add(exchange.getRequestHeaders().getFirst("Content-Type"));
              methods.add(exchange.getRequestMethod());
              entered.countDown();
              try {
                gate.await(5, TimeUnit.SECONDS);
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
              }
              if (location != null) exchange.getResponseHeaders().set("Location", location);
              exchange.sendResponseHeaders(status.get(), -1);
            }
          });
      server.start();
    }

    String endpoint() {
      return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/traces";
    }

    @Override
    public void close() {
      gate.countDown();
      server.stop(0);
    }
  }
}

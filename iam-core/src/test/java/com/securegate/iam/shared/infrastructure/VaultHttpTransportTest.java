package com.securegate.iam.shared.infrastructure;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.securegate.iam.bootstrap.RestrictedHttpTracePropagator;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.tracing.otel.bridge.*;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.trace.*;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;

class VaultHttpTransportTest {
  final ConcurrentLinkedQueue<SpanData> spans = new ConcurrentLinkedQueue<>();
  final ConcurrentLinkedQueue<Map<String, String>> seen = new ConcurrentLinkedQueue<>();
  final AtomicInteger status = new AtomicInteger(200);
  SdkTracerProvider provider;
  OtelTracer tracer;
  VaultHttpTransport transport;
  HttpServer server;
  URI address;
  HttpClient http;

  @BeforeEach
  void start() throws Exception {
    provider =
        SdkTracerProvider.builder()
            .addSpanProcessor(
                new SpanProcessor() {
                  public void onStart(Context parent, ReadWriteSpan span) {}

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
    var propagator =
        new OtelPropagator(
            ContextPropagators.create(new RestrictedHttpTracePropagator()), provider.get("test"));
    transport = new VaultHttpTransport(() -> tracer, () -> propagator);
    http = HttpClient.newHttpClient();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          var entry = new HashMap<String, String>();
          entry.put(
              "body", new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          for (String key : List.of("traceparent", "tracestate", "baggage", "X-Vault-Token")) {
            var value = exchange.getRequestHeaders().getFirst(key);
            if (value != null) entry.put(key, value);
          }
          seen.add(entry);
          exchange.getResponseHeaders().set("Location", "/unapproved");
          exchange.sendResponseHeaders(status.get(), 2);
          try (var out = exchange.getResponseBody()) {
            out.write("{}".getBytes(StandardCharsets.UTF_8));
          }
          exchange.close();
        });
    server.start();
    address = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
  }

  @AfterEach
  void stop() {
    server.stop(0);
    http.close();
    provider.close();
  }

  HttpRequest request(String path) {
    return HttpRequest.newBuilder(address.resolve(path))
        .timeout(Duration.ofSeconds(2))
        .header("X-Vault-Token", "synthetic-token")
        .header("baggage", "secret=synthetic-secret")
        .POST(HttpRequest.BodyPublishers.ofString("synthetic-body-secret"))
        .build();
  }

  @Test
  void defaultTransportPreservesRequestAndDoesNotCreateContext() throws Exception {
    VaultHttpTransport.untraced()
        .send(
            http,
            request("/v1/transit/encrypt/key"),
            HttpResponse.BodyHandlers.ofString(),
            address);
    assertThat(seen.element())
        .containsEntry("body", "synthetic-body-secret")
        .containsEntry("X-Vault-Token", "synthetic-token");
    assertThat(seen.element()).doesNotContainKey("traceparent");
    assertThat(spans).isEmpty();
  }

  @Test
  void recordsChildWithoutSecretsAndRestoresOuterScope() throws Exception {
    var parent = tracer.nextSpan().name("parent").start();
    try (var scope = tracer.withSpan(parent)) {
      transport.send(
          http, request("/v1/transit/encrypt/key"), HttpResponse.BodyHandlers.ofString(), address);
      assertThat(tracer.currentSpan().context().spanId()).isEqualTo(parent.context().spanId());
    } finally {
      parent.end();
    }
    var child =
        spans.stream().filter(s -> s.getName().equals("vault.http")).findFirst().orElseThrow();
    assertThat(child.getParentSpanId()).isEqualTo(parent.context().spanId());
    assertThat(child.getAttributes().size()).isEqualTo(4);
    assertThat(child.getAttributes().asMap().toString())
        .contains("transit.encrypt", "2xx", "false");
    assertThat(child.toString()).doesNotContain("synthetic", "encrypt/key", "127.0.0.1");
    assertThat(child.getEvents()).isEmpty();
    assertThat(seen.element())
        .containsEntry("body", "synthetic-body-secret")
        .containsEntry("X-Vault-Token", "synthetic-token");
    assertThat(seen.element()).doesNotContainKeys("baggage", "tracestate");
    assertThat(seen.element().get("traceparent")).contains(child.getTraceId(), child.getSpanId());
    assertThat(tracer.currentSpan()).isNull();
  }

  @Test
  void skipsOtherOriginsPathsQueriesAndRedirectFollowingClients() throws Exception {
    for (String path : List.of("/unapproved", "/v1/transit/encrypt/key?token=synthetic-secret")) {
      transport.send(http, request(path), HttpResponse.BodyHandlers.ofString(), address);
    }
    transport.send(
        http,
        request("/v1/transit/encrypt/key"),
        HttpResponse.BodyHandlers.ofString(),
        URI.create("http://localhost:" + address.getPort()));
    try (var following =
        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build()) {
      transport.send(
          following,
          request("/v1/transit/encrypt/key"),
          HttpResponse.BodyHandlers.ofString(),
          address);
    }
    assertThat(seen).hasSize(4);
    for (var entry : seen)
      assertThat(entry).doesNotContainKeys("traceparent", "baggage", "tracestate");
    assertThat(spans).isEmpty();
  }

  @Test
  void preservesNonSuccessAndDoesNotFollowRedirects() throws Exception {
    for (int code : List.of(403, 500, 302)) {
      status.set(code);
      assertThat(
              transport
                  .send(
                      http,
                      request("/v1/auth/approle/login"),
                      HttpResponse.BodyHandlers.ofString(),
                      address)
                  .statusCode())
          .isEqualTo(code);
    }
    assertThat(seen).hasSize(3);
    assertThat(spans).hasSize(3);
    assertThat(spans)
        .allSatisfy(s -> assertThat(s.getAttributes().asMap().toString()).contains("true"));
    assertThat(tracer.currentSpan()).isNull();
  }

  @Test
  void propagatesIoAndInterruptionWithoutRecordingExceptionText() throws Exception {
    var broken = mock(HttpClient.class);
    when(broken.followRedirects()).thenReturn(HttpClient.Redirect.NEVER);
    when(broken.send(any(), any(HttpResponse.BodyHandler.class)))
        .thenThrow(new java.io.IOException("synthetic-io-secret"))
        .thenThrow(new InterruptedException("synthetic-interrupt-secret"));
    assertThatThrownBy(
            () ->
                transport.send(
                    broken,
                    request("/v1/transit/sign/key"),
                    HttpResponse.BodyHandlers.ofString(),
                    address))
        .isInstanceOf(java.io.IOException.class);
    assertThatThrownBy(
            () ->
                transport.send(
                    broken,
                    request("/v1/transit/sign/key"),
                    HttpResponse.BodyHandlers.ofString(),
                    address))
        .isInstanceOf(InterruptedException.class);
    assertThat(spans)
        .hasSize(2)
        .allSatisfy(
            s -> {
              assertThat(s.getEvents()).isEmpty();
              assertThat(s.getAttributes().asMap().toString()).contains("unknown", "true");
              assertThat(s.toString()).doesNotContain("synthetic", "Exception");
            });
    assertThat(tracer.currentSpan()).isNull();
  }
}

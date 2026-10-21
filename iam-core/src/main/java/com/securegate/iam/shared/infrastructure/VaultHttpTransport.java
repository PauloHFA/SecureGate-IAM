package com.securegate.iam.shared.infrastructure;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

/** Synchronous Vault transport tracing. Never retains tokens, bodies, leases or parent context. */
public final class VaultHttpTransport {
  private static final VaultHttpTransport PLAIN = new VaultHttpTransport(() -> null, () -> null);
  private static final Set<String> CONTEXT_HEADERS =
      Set.of(
          "traceparent",
          "tracestate",
          "baggage",
          "b3",
          "x-b3-traceid",
          "x-b3-spanid",
          "x-b3-parentspanid",
          "x-b3-sampled",
          "x-b3-flags");
  private static final Set<String> METHODS =
      Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "TRACE");
  private final Supplier<Tracer> tracers;
  private final Supplier<Propagator> propagators;

  public VaultHttpTransport(Supplier<Tracer> tracers, Supplier<Propagator> propagators) {
    this.tracers = tracers;
    this.propagators = propagators;
  }

  public static VaultHttpTransport untraced() {
    return PLAIN;
  }

  public <T> HttpResponse<T> send(
      HttpClient http, HttpRequest request, HttpResponse.BodyHandler<T> handler, URI vault)
      throws IOException, InterruptedException {
    Tracer tracer = tracers.get();
    if (tracer == null) return http.send(request, handler);
    var builder =
        HttpRequest.newBuilder(
            request, (key, value) -> !CONTEXT_HEADERS.contains(key.toLowerCase(Locale.ROOT)));
    String operation = operation(request.uri().getPath());
    Propagator propagator = propagators.get();
    if (propagator == null
        || operation == null
        || !sameOrigin(vault, request.uri())
        || request.uri().getUserInfo() != null
        || request.uri().getQuery() != null
        || request.uri().getFragment() != null
        || http.followRedirects() != HttpClient.Redirect.NEVER) {
      return http.send(builder.build(), handler);
    }
    Span span = tracer.spanBuilder().name("vault.http").kind(Span.Kind.CLIENT).start();
    int status = 0;
    boolean failure = true;
    try (var scope = tracer.withSpan(span)) {
      propagator.inject(
          span.context(),
          builder,
          (target, key, value) -> {
            if ("traceparent".equals(key)) target.setHeader(key, value);
          });
      var response = http.send(builder.build(), handler);
      status = response.statusCode();
      failure = status < 200 || status >= 300;
      return response;
    } finally {
      span.tag("vault.operation", operation);
      span.tag("http.method", METHODS.contains(request.method()) ? request.method() : "OTHER");
      span.tag(
          "http.status_class", status >= 100 && status < 600 ? status / 100 + "xx" : "unknown");
      span.tag("http.failure", failure ? "true" : "false");
      span.end();
    }
  }

  private static boolean sameOrigin(URI first, URI second) {
    return first != null
        && first.getHost() != null
        && second.getHost() != null
        && first.getScheme().equalsIgnoreCase(second.getScheme())
        && first.getHost().equalsIgnoreCase(second.getHost())
        && port(first) == port(second);
  }

  private static int port(URI uri) {
    return uri.getPort() >= 0
        ? uri.getPort()
        : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
  }

  private static String operation(String path) {
    if (path == null) return null;
    if (path.equals("/v1/auth/approle/login")) return "approle.login";
    if (path.equals("/v1/auth/token/renew-self")) return "token.renew";
    if (path.equals("/v1/auth/token/revoke-self")) return "token.revoke";
    if (path.matches("/v1/transit/(keys|sign|encrypt|decrypt)/[A-Za-z0-9_-]{1,64}")) {
      return "transit." + path.split("/")[3];
    }
    if (path.equals("/v1/database/creds/iam-runtime")) return "database.issue";
    if (path.matches("/v1/pam-database/creds/diagnostics-(10|30|60|120|240)")) return "pam.issue";
    if (path.matches(
        "/v1/sys/leases/(renew|revoke)/(database/creds/iam-runtime|pam-database/creds/diagnostics-(10|30|60|120|240))/[A-Za-z0-9_-]{1,200}")) {
      return path.startsWith("/v1/sys/leases/renew/") ? "lease.renew" : "lease.revoke";
    }
    return null;
  }
}

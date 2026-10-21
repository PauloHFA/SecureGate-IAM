package com.securegate.enforcer.infrastructure;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Optional loopback-only listener; the enforcer remains a non-web Spring application. */
public final class MetricsHttpServer implements AutoCloseable {
  private final HttpServer server;
  private final ThreadPoolExecutor executor;
  private final PrometheusMeterRegistry meters;
  private final byte[] expected;

  public MetricsHttpServer(PrometheusMeterRegistry meters, int port, String tokenFile) {
    this.meters = meters;
    this.expected = digest(readToken(tokenFile));
    try {
      this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 16);
    } catch (IOException | RuntimeException unavailable) {
      throw new IllegalStateException("Metrics listener unavailable.");
    }
    this.executor =
        new ThreadPoolExecutor(
            2,
            2,
            0,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16),
            work -> {
              Thread thread = new Thread(work, "enforcer-metrics");
              thread.setDaemon(true);
              return thread;
            },
            new ThreadPoolExecutor.AbortPolicy());
    server.setExecutor(executor);
    server.createContext("/", this::handle);
  }

  public void start() {
    server.start();
  }

  public int port() {
    return server.getAddress().getPort();
  }

  private void handle(HttpExchange exchange) throws IOException {
    try (exchange) {
      var values = exchange.getRequestHeaders().get("Authorization");
      String header = values != null && values.size() == 1 ? values.getFirst() : "";
      exchange.getResponseHeaders().set("Cache-Control", "no-store");
      if (header.length() > 512
          || !header.regionMatches(true, 0, "Bearer ", 0, 7)
          || !MessageDigest.isEqual(expected, digest(header.substring(7)))) {
        reply(exchange, 401, "Unauthorized\n");
        return;
      }
      if (!exchange.getRequestURI().getRawPath().equals("/metrics")) {
        reply(exchange, 404, "Not found\n");
        return;
      }
      if (!exchange.getRequestMethod().equals("GET")) {
        exchange.getResponseHeaders().set("Allow", "GET");
        reply(exchange, 405, "Method not allowed\n");
        return;
      }
      if (exchange.getRequestURI().getRawQuery() != null) {
        reply(exchange, 400, "Invalid query\n");
        return;
      }
      String body;
      try {
        body = meters.scrape();
      } catch (RuntimeException unavailable) {
        reply(exchange, 503, "Metrics unavailable\n");
        return;
      }
      reply(exchange, 200, body);
    }
  }

  private static void reply(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
  }

  private static String readToken(String location) {
    try {
      if (location == null || location.isBlank()) {
        throw new IOException();
      }
      Path file = Path.of(location);
      if (!Files.isRegularFile(file) || Files.size(file) > 512) {
        throw new IOException();
      }
      String token = Files.readString(file, StandardCharsets.UTF_8).strip();
      if (token.length() < 32
          || token.length() > 256
          || token.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))) {
        throw new IOException();
      }
      return token;
    } catch (IOException | RuntimeException invalid) {
      throw new IllegalStateException(
          "Metrics requires a readable token file (32-256 non-whitespace characters).");
    }
  }

  private static byte[] digest(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("SHA-256 unavailable");
    }
  }

  @Override
  public void close() {
    server.stop(0);
    executor.shutdownNow();
    try {
      executor.awaitTermination(2, TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}

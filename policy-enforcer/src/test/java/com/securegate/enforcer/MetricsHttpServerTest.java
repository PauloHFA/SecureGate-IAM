package com.securegate.enforcer;

import static org.junit.jupiter.api.Assertions.*;

import com.securegate.enforcer.infrastructure.MetricsHttpServer;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MetricsHttpServerTest {
  @TempDir Path directory;
  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(1)).build();
  private final String token = UUID.randomUUID().toString() + UUID.randomUUID();

  @Test
  void servesOnlyExactAuthenticatedGetAndClosesListener() throws Exception {
    Path file = directory.resolve("credential");
    Files.writeString(file, token);
    int port;
    var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    try (var server = new MetricsHttpServer(registry, 0, file.toString())) {
      registry.counter("iam.enforcer.block-policy.messages", "result", "ACK").increment();
      server.start();
      port = server.port();
      var response = get(port, "/metrics", token);
      assertEquals(200, response.statusCode());
      assertTrue(response.body().contains("iam_enforcer_block_policy_messages_total"));
      assertFalse(response.body().contains(token));
      assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(""));
      assertTrue(response.headers().firstValue("Set-Cookie").isEmpty());
      assertEquals(401, get(port, "/metrics", null).statusCode());
      assertEquals(401, get(port, "/metrics", "incorrect-token").statusCode());
      assertEquals(404, get(port, "/actuator/env", token).statusCode());
      assertEquals(400, get(port, "/metrics?token=synthetic-query", token).statusCode());
      assertEquals(
          405,
          client
              .send(
                  request(port, "/metrics")
                      .header("Authorization", "Bearer " + token)
                      .POST(HttpRequest.BodyPublishers.noBody())
                      .build(),
                  HttpResponse.BodyHandlers.discarding())
              .statusCode());
      assertEquals(
          401,
          client
              .send(
                  request(port, "/metrics")
                      .header("Authorization", "Bearer " + token)
                      .header("Authorization", "Bearer " + token)
                      .GET()
                      .build(),
                  HttpResponse.BodyHandlers.discarding())
              .statusCode());
      // Expected digest is loaded once, so credential rotation requires process restart.
      Files.writeString(file, UUID.randomUUID().toString());
      assertEquals(200, get(port, "/metrics", token).statusCode());
    }
    registry.close();
    assertThrows(java.io.IOException.class, () -> get(port, "/metrics", token));
  }

  @Test
  void rejectsMissingAndMalformedCredentialWithoutLeakingPathOrCause() throws Exception {
    var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    try {
      assertThrows(IllegalStateException.class, () -> new MetricsHttpServer(registry, 0, ""));
      assertThrows(
          IllegalStateException.class,
          () -> new MetricsHttpServer(registry, 0, directory.resolve("absent").toString()));
      for (String invalid :
          java.util.List.of(
              "", "short", "x".repeat(513), "x".repeat(40) + " bad", "x".repeat(40) + "\u0000")) {
        Path file = directory.resolve("bad");
        Files.writeString(file, invalid);
        var error =
            assertThrows(
                IllegalStateException.class,
                () -> new MetricsHttpServer(registry, 0, file.toString()));
        assertFalse(error.getMessage().contains(directory.toString()));
        assertNull(error.getCause());
      }
    } finally {
      registry.close();
    }
  }

  private HttpRequest.Builder request(int port, String path) {
    return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
        .timeout(java.time.Duration.ofSeconds(2));
  }

  private HttpResponse<String> get(int port, String path, String credential) throws Exception {
    var request = request(port, path);
    if (credential != null) {
      request.header("Authorization", "Bearer " + credential);
    }
    return client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
  }
}

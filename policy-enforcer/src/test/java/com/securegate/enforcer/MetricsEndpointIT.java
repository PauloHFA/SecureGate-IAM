package com.securegate.enforcer;

import static org.junit.jupiter.api.Assertions.*;

import com.securegate.enforcer.infrastructure.MetricsHttpServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@AutoConfigureObservability
@SpringBootTest(properties = "enforcer.metrics.enabled=true")
class MetricsEndpointIT {
  private static final String TOKEN = UUID.randomUUID().toString() + UUID.randomUUID();
  private static final Path FILE =
      Path.of("target/metrics-credentials", UUID.randomUUID().toString(), "token");

  @DynamicPropertySource
  static void settings(DynamicPropertyRegistry properties) throws Exception {
    Files.createDirectories(FILE.getParent());
    Files.writeString(FILE, TOKEN + "\n");
    try (var socket = new java.net.ServerSocket(0)) {
      int port = socket.getLocalPort();
      properties.add("enforcer.metrics.port", () -> port);
    }
    properties.add("enforcer.metrics.token-file", () -> FILE.toAbsolutePath().toString());
  }

  @Autowired MetricsHttpServer server;
  @Autowired ApplicationContext context;

  @Test
  void optionalEndpointUsesRealJvmRegistryWithoutTurningApplicationIntoServlet() throws Exception {
    assertFalse(
        context instanceof org.springframework.boot.web.context.WebServerApplicationContext);
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/metrics"))
            .header("Authorization", "Bearer " + TOKEN)
            .timeout(java.time.Duration.ofSeconds(3))
            .GET()
            .build();
    var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode());
    assertTrue(response.body().contains("jvm_memory_used_bytes"));
    assertTrue(response.body().contains("application=\"policy-enforcer\""));
    assertFalse(response.body().contains(TOKEN));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new com.securegate.enforcer.bootstrap.MetricsConfiguration.Settings(
                true, 12, "unused"));
  }

  @AfterAll
  static void cleanup() throws Exception {
    Files.deleteIfExists(FILE);
    Files.deleteIfExists(FILE.getParent());
  }
}

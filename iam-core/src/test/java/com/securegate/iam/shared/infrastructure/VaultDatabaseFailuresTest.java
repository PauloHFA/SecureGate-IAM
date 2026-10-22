package com.securegate.iam.shared.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class VaultDatabaseFailuresTest {
  @TempDir Path files;
  private HttpServer server;
  private String mode = "bad-name";
  private final AtomicInteger revokes = new AtomicInteger();

  @BeforeEach
  void setup() throws Exception {
    Files.writeString(files.resolve("role"), "synthetic-role");
    Files.writeString(files.resolve("secret"), "synthetic-secret");
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/",
        exchange -> {
          String path = exchange.getRequestURI().getPath();
          Object response;
          int status = 200;
          if (path.endsWith("login")) {
            response =
                Map.of(
                    "auth",
                    Map.of(
                        "client_token",
                        "synthetic-token",
                        "lease_duration",
                        mode.equals("short-token") ? 1 : 3600));
            if (mode.equals("login")) {
              status = 403;
            }
          } else if (path.endsWith("revoke-self")) {
            revokes.incrementAndGet();
            response = Map.of();
          } else {
            assertEquals("synthetic-token", exchange.getRequestHeaders().getFirst("X-Vault-Token"));
            response =
                Map.of(
                    "lease_id",
                    mode.equals("wrong-lease")
                        ? "other/lease"
                        : "database/creds/iam-runtime/synthetic",
                    "lease_duration",
                    mode.equals("short-lease") ? 1 : 600,
                    "renewable",
                    true,
                    "data",
                    Map.of(
                        "username",
                        mode.equals("bad-name") ? "root" : "sg_" + "a".repeat(20),
                        "password",
                        mode.equals("blank-password") ? "" : "synthetic-password"));
            if (mode.equals("forbidden")) {
              status = 403;
            }
          }
          byte[] bytes =
              mode.equals("json") && path.contains("creds/")
                  ? "invalid synthetic-password".getBytes(java.nio.charset.StandardCharsets.UTF_8)
                  : new ObjectMapper().writeValueAsBytes(response);
          exchange.sendResponseHeaders(status, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private URI address() {
    return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "login",
        "short-token",
        "wrong-lease",
        "short-lease",
        "bad-name",
        "blank-password",
        "forbidden",
        "json"
      })
  void rejectsInvalidResponseWithoutExposingExternalCauseAndRevokesParent(String scenario) {
    mode = scenario;
    var failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                new VaultDatabaseDataSource(
                    "jdbc:postgresql://127.0.0.1:1/synthetic",
                    address(),
                    files.resolve("role"),
                    files.resolve("secret"),
                    new SimpleMeterRegistry()));
    assertEquals("Vault database unavailable.", failure.getMessage());
    assertNull(failure.getCause());
    if (!scenario.equals("login")) {
      assertEquals(1, revokes.get());
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "http://example.test",
        "http://127.0.0.1/path",
        "http://user:pass@127.0.0.1",
        "http://127.0.0.1?query",
        "http://127.0.0.1#fragment"
      })
  void refusesUnsafeVaultAddress(String value) {
    assertThrows(
        IllegalStateException.class,
        () ->
            new VaultDatabaseDataSource(
                "jdbc:postgresql://127.0.0.1:1/synthetic",
                URI.create(value),
                files.resolve("role"),
                files.resolve("secret"),
                new SimpleMeterRegistry()));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "jdbc:mysql://localhost/db",
        "jdbc:postgresql://user:password@localhost/db",
        "jdbc:postgresql://localhost/db?password=synthetic",
        "jdbc:postgresql://localhost/db#fragment"
      })
  void rejectsCredentialsInJdbcUrlBeforeAccessingVault(String url) {
    assertThrows(
        IllegalStateException.class,
        () ->
            new VaultDatabaseDataSource(
                url,
                address(),
                files.resolve("role"),
                files.resolve("secret"),
                new SimpleMeterRegistry()));
    assertEquals(0, revokes.get());
  }

  @Test
  void missingMigrationConfigurationDoesNotExposeCause() {
    var failure =
        assertThrows(
            IllegalStateException.class,
            () -> com.securegate.iam.bootstrap.DatabaseMigration.run(Map.of()));
    assertEquals("Database migration unavailable.", failure.getMessage());
    assertNull(failure.getCause());
  }
}

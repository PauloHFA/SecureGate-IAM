package com.securegate.iam.shared.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
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

class VaultDatabaseSessionLifecycleTest {
  @TempDir Path files;
  private HttpServer server;
  private String mode = "normal";
  private final AtomicInteger renewals = new AtomicInteger();
  private final AtomicInteger revocations = new AtomicInteger();

  @BeforeEach
  void setup() throws Exception {
    Files.writeString(files.resolve("role"), "synthetic-role");
    Files.writeString(files.resolve("secret"), "synthetic-secret");
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/",
        exchange -> {
          String path = exchange.getRequestURI().getPath();
          int status = 200;
          Object response;
          if (path.endsWith("revoke-self")) {
            int count = revocations.incrementAndGet();
            response = Map.of();
            if (mode.equals("retry") && count == 1) {
              status = 503;
            }
          } else {
            boolean login = path.endsWith("login");
            if (!login) {
              renewals.incrementAndGet();
              assertEquals(
                  "synthetic-token", exchange.getRequestHeaders().getFirst("X-Vault-Token"));
            }
            long ttl = login ? 30 : mode.equals("zero-ttl") ? 0 : mode.equals("short-ttl") ? 5 : 60;
            response =
                Map.of(
                    "auth",
                    Map.of(
                        "client_token",
                        !login && mode.equals("wrong-token") ? "other-token" : "synthetic-token",
                        "lease_duration",
                        ttl,
                        "renewable",
                        !mode.equals("not-renewable")));
            if (!login && mode.equals("forbidden")) {
              status = 403;
            }
            if (!login && mode.equals("server-error")) {
              status = 503;
            }
          }
          byte[] bytes =
              path.endsWith("renew-self") && mode.equals("bad-json")
                  ? "invalid synthetic-secret".getBytes(java.nio.charset.StandardCharsets.UTF_8)
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

  private VaultDatabaseSession session() {
    return new VaultDatabaseSession(
        URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
        files.resolve("role"),
        files.resolve("secret"));
  }

  @Test
  void validatedRenewalExtendsSameToken() {
    try (var session = session()) {
      long before = session.deadline();
      session.renew();
      assertTrue(session.deadline() > before);
      assertEquals(1, renewals.get());
      assertEquals("VaultDatabaseSession[redacted]", session.toString());
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"forbidden", "wrong-token", "zero-ttl", "bad-json", "server-error", "short-ttl"})
  void rejectedResponsesNeverExtendDeadlineOrExposeData(String scenario) {
    try (var session = session()) {
      long before = session.deadline();
      mode = scenario;
      var failure = assertThrows(VaultDatabaseSession.Unavailable.class, session::renew);
      assertEquals("Vault database unavailable.", failure.getMessage());
      assertNull(failure.getCause());
      assertTrue(session.deadline() <= before);
      assertEquals(
          !scenario.equals("server-error") && !scenario.equals("short-ttl"), failure.terminal);
    }
  }

  @Test
  void nonRenewableTokenRequestsReplacementWithoutCallingRenew() {
    mode = "not-renewable";
    try (var session = session()) {
      assertFalse(assertThrows(VaultDatabaseSession.Unavailable.class, session::renew).terminal);
      assertEquals(0, renewals.get());
    }
  }

  @Test
  void failedRevocationCanRetryWithSameToken() {
    mode = "retry";
    try (var session = session()) {
      assertThrows(VaultDatabaseSession.Unavailable.class, session::revoke);
      session.revoke();
      session.revoke();
      assertEquals(2, revocations.get());
    }
  }
}

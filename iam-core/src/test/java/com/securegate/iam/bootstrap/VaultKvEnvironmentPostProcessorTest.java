package com.securegate.iam.bootstrap;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
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
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

class VaultKvEnvironmentPostProcessorTest {
  @TempDir Path directory;
  private HttpServer server;
  private MockEnvironment environment;
  private String scenario = "ok";
  private final AtomicInteger reads = new AtomicInteger();
  private final AtomicInteger revokes = new AtomicInteger();

  @BeforeEach
  void setup() throws Exception {
    Files.writeString(directory.resolve("role"), "synthetic-role");
    Files.writeString(directory.resolve("secret"), "synthetic-secret");
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/",
        exchange -> {
          String path = exchange.getRequestURI().getPath();
          Object response;
          int status = 200;
          if (path.endsWith("login")) {
            response =
                Map.of("auth", Map.of("client_token", "synthetic-token", "lease_duration", 3600));
            if (scenario.equals("login")) {
              status = 400;
            }
          } else {
            assertEquals("synthetic-token", exchange.getRequestHeaders().getFirst("X-Vault-Token"));
            if (path.endsWith("revoke-self")) {
              revokes.incrementAndGet();
              response = Map.of();
              if (scenario.equals("revoke")) {
                status = 403;
              }
            } else {
              reads.incrementAndGet();
              assertEquals("/v1/secret/data/securegate/iam-core", path);
              assertEquals("GET", exchange.getRequestMethod());
              response =
                  Map.of(
                      "data",
                      Map.of(
                          "data",
                          scenario.equals("missing") ? Map.of() : values(),
                          "metadata",
                          Map.of(
                              "version",
                              scenario.equals("version") ? 0 : 1,
                              "destroyed",
                              scenario.equals("destroyed"),
                              "deletion_time",
                              scenario.equals("deleted") ? "synthetic-date" : "")));
              if (scenario.equals("forbidden")) {
                status = 403;
              }
              if (scenario.equals("not-found")) {
                status = 404;
              }
            }
          }
          byte[] bytes =
              scenario.equals("json") && path.contains("/data/")
                  ? "invalid synthetic-secret".getBytes(java.nio.charset.StandardCharsets.UTF_8)
                  : new ObjectMapper().writeValueAsBytes(response);
          exchange.sendResponseHeaders(status, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    environment =
        new MockEnvironment()
            .withProperty("IAM_VAULT_KV_ENABLED", "true")
            .withProperty("IAM_VAULT_ADDRESS", "http://127.0.0.1:" + server.getAddress().getPort())
            .withProperty("IAM_VAULT_ROLE_ID_FILE", directory.resolve("role").toString())
            .withProperty("IAM_VAULT_SECRET_ID_FILE", directory.resolve("secret").toString())
            .withProperty("spring.datasource.password", "local-must-not-win");
  }

  static Map<String, String> values() {
    return Map.of(
        "postgres_username",
        "synthetic-user",
        "postgres_password",
        "synthetic-db-password",
        "redis_password",
        "synthetic-redis-password",
        "rabbitmq_username",
        "synthetic-broker-user",
        "rabbitmq_password",
        "synthetic-broker-password",
        "untrusted.property",
        "must-not-be-imported");
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private void process() {
    new VaultKvEnvironmentPostProcessor()
        .postProcessEnvironment(environment, new SpringApplication());
  }

  @Test
  void importsOnlyAllowedPropertiesAtomicallyWithPriorityAndRevokesToken() {
    process();
    assertEquals("synthetic-db-password", environment.getProperty("spring.datasource.password"));
    assertEquals(
        "synthetic-redis-password", environment.getProperty("iam.resources.enforcement.password"));
    assertEquals("synthetic-broker-user", environment.getProperty("iam.audit.consumer.username"));
    assertEquals(
        "synthetic-broker-password", environment.getProperty("iam.outbox.publisher.password"));
    assertNull(environment.getProperty("untrusted.property"));
    assertEquals(
        "VaultKvPropertySource[redacted]",
        environment.getPropertySources().get("vault-kv-startup").toString());
    assertEquals(1, reads.get());
    assertEquals(1, revokes.get());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "login",
        "forbidden",
        "not-found",
        "missing",
        "destroyed",
        "deleted",
        "version",
        "json",
        "revoke"
      })
  void failureDoesNotPublishPartialSnapshotOrExposeCauses(String mode) {
    scenario = mode;
    IllegalStateException failure = assertThrows(IllegalStateException.class, this::process);
    assertEquals("Vault KV startup unavailable.", failure.getMessage());
    assertNull(failure.getCause());
    assertNull(environment.getPropertySources().get("vault-kv-startup"));
    if (!mode.equals("login")) {
      assertTrue(revokes.get() >= 1);
    }
  }

  @Test
  void disabledDoesNotReadFilesOrContactVault() {
    environment.setProperty("IAM_VAULT_KV_ENABLED", "false");
    environment.setProperty("IAM_VAULT_ROLE_ID_FILE", "nonexistent");
    process();
    assertEquals(0, reads.get());
    assertNull(environment.getPropertySources().get("vault-kv-startup"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "http://example.com",
        "http://127.0.0.1/path",
        "http://user:pass@127.0.0.1",
        "http://127.0.0.1?query",
        "http://127.0.0.1#fragment"
      })
  void rejectsUnsafeAddressBeforeLogin(String address) {
    environment.setProperty("IAM_VAULT_ADDRESS", address);
    assertThrows(IllegalStateException.class, this::process);
    assertEquals(0, reads.get());
  }

  @Test
  void rejectsMissingAppRoleFile() throws Exception {
    Files.delete(directory.resolve("secret"));
    assertThrows(IllegalStateException.class, this::process);
  }
}

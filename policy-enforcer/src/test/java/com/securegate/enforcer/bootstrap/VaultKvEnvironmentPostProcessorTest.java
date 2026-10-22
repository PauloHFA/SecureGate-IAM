package com.securegate.enforcer.bootstrap;

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
                Map.of(
                    "auth",
                    Map.of(
                        "client_token",
                        "synthetic-token",
                        "lease_duration",
                        scenario.equals("short-lease") ? 1 : 3600));
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
              assertEquals("/v1/secret/data/securegate/policy-enforcer", path);
              assertEquals("GET", exchange.getRequestMethod());
              response =
                  Map.of(
                      "data",
                      Map.of(
                          "data",
                          scenario.equals("missing") ? Map.of() : scenarioValues(),
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
            .withProperty("ENFORCER_VAULT_KV_ENABLED", "true")
            .withProperty(
                "ENFORCER_VAULT_ADDRESS", "http://127.0.0.1:" + server.getAddress().getPort())
            .withProperty("ENFORCER_VAULT_ROLE_ID_FILE", directory.resolve("role").toString())
            .withProperty("ENFORCER_VAULT_SECRET_ID_FILE", directory.resolve("secret").toString())
            .withProperty("spring.data.redis.password", "local-must-not-win");
  }

  private Map<String, Object> scenarioValues() {
    var result = new java.util.LinkedHashMap<String, Object>(values());
    switch (scenario) {
      case "blank" -> result.put("redis_password", " ");
      case "non-text" -> result.put("rabbitmq_password", 42);
      case "control" -> result.put("rabbitmq_username", "bad\nvalue");
      case "oversize" -> result.put("redis_password", "x".repeat(4097));
      default -> {}
    }
    return result;
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
    assertEquals("synthetic-redis-password", environment.getProperty("spring.data.redis.password"));
    assertEquals("synthetic-broker-user", environment.getProperty("enforcer.consumer.username"));
    assertEquals(
        "synthetic-broker-password", environment.getProperty("enforcer.consumer.password"));
    assertNull(environment.getProperty("untrusted.property"));
    assertNull(environment.getProperty("spring.datasource.password"));
    assertEquals(
        3,
        ((org.springframework.core.env.MapPropertySource)
                environment.getPropertySources().get("vault-kv-startup"))
            .getSource()
            .size());
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
        "revoke",
        "short-lease",
        "blank",
        "non-text",
        "control",
        "oversize"
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
    environment.setProperty("ENFORCER_VAULT_KV_ENABLED", "false");
    environment.setProperty("ENFORCER_VAULT_ROLE_ID_FILE", "nonexistent");
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
    environment.setProperty("ENFORCER_VAULT_ADDRESS", address);
    assertThrows(IllegalStateException.class, this::process);
    assertEquals(0, reads.get());
  }

  @ParameterizedTest
  @ValueSource(strings = {"yes", "1", "", " true "})
  void rejectsMalformedFlag(String value) {
    environment.setProperty("ENFORCER_VAULT_KV_ENABLED", value);
    assertThrows(IllegalStateException.class, this::process);
    assertEquals(0, reads.get());
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "bad\nvalue"})
  void rejectsInvalidCredentialFile(String value) throws Exception {
    Files.writeString(directory.resolve("secret"), value);
    assertThrows(IllegalStateException.class, this::process);
    assertEquals(0, reads.get());
  }

  @Test
  void rejectsMissingAppRoleFile() throws Exception {
    Files.delete(directory.resolve("secret"));
    assertThrows(IllegalStateException.class, this::process);
  }
}

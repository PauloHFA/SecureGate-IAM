package com.securegate.iam;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securegate.iam.bootstrap.VaultKvEnvironmentPostProcessor;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class VaultKvBootstrapIT {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String ROOT = UUID.randomUUID().toString();
  @TempDir static Path directory;

  @Container
  static final GenericContainer<?> VAULT =
      new GenericContainer<>("hashicorp/vault:1.21.4")
          .withEnv("VAULT_DEV_ROOT_TOKEN_ID", ROOT)
          .withEnv("SKIP_SETCAP", "true")
          .withCommand("server", "-dev", "-dev-listen-address=0.0.0.0:8200")
          .withExposedPorts(8200)
          .waitingFor(Wait.forHttp("/v1/sys/health").forStatusCode(200));

  @Container
  static final PostgreSQLContainer<?> DATABASE =
      new PostgreSQLContainer<>("postgres:16.15-alpine")
          .withDatabaseName("kv_test")
          .withUsername("kv_test")
          .withPassword(UUID.randomUUID().toString());

  private static String address() {
    return "http://127.0.0.1:" + VAULT.getMappedPort(8200);
  }

  @BeforeAll
  static void provision() throws Exception {
    // Vault dev already mounts KV v2 at secret/.
    admin("sys/auth/approle", Map.of("type", "approle"));
    admin(
        "sys/policies/acl/securegate-iam-core",
        Map.of("policy", Files.readString(Path.of("../infra/vault/iam-core.hcl"))));
    admin(
        "auth/approle/role/iam-core",
        Map.of("token_policies", "securegate-iam-core", "token_ttl", "1h"));
    Files.writeString(
        directory.resolve("role"),
        admin("auth/approle/role/iam-core/role-id", null).path("data").path("role_id").asText());
    Files.writeString(
        directory.resolve("secret"),
        admin("auth/approle/role/iam-core/secret-id", Map.of())
            .path("data")
            .path("secret_id")
            .asText());
    admin(
        "secret/data/securegate/iam-core",
        Map.of(
            "data",
            Map.of(
                "postgres_username",
                DATABASE.getUsername(),
                "postgres_password",
                DATABASE.getPassword(),
                "redis_password",
                UUID.randomUUID().toString(),
                "rabbitmq_username",
                "synthetic-user",
                "rabbitmq_password",
                UUID.randomUUID().toString())));
  }

  @Test
  void discoveredProcessorBootsRealIamAndFlywayUsingOnlyKvDatabaseCredentials() {
    try (var context =
        new SpringApplicationBuilder(IamCoreApplication.class)
            .run(
                "--server.port=0",
                "--IAM_VAULT_KV_ENABLED=true",
                "--IAM_VAULT_ADDRESS=" + address(),
                "--IAM_VAULT_ROLE_ID_FILE=" + directory.resolve("role"),
                "--IAM_VAULT_SECRET_ID_FILE=" + directory.resolve("secret"),
                "--IAM_DATABASE_URL=" + DATABASE.getJdbcUrl(),
                "--IAM_DATABASE_USERNAME=invalid-local-user",
                "--IAM_DATABASE_PASSWORD=invalid-local-password")) {
      assertEquals(
          DATABASE.getUsername(),
          context.getBean(JdbcTemplate.class).queryForObject("select current_user", String.class));
      assertTrue(
          context
                  .getBean(JdbcTemplate.class)
                  .queryForObject(
                      "select count(*) from flyway_schema_history where success", Integer.class)
              >= 16);
      assertEquals(
          DATABASE.getPassword(),
          context.getEnvironment().getProperty("spring.datasource.password"));
      assertEquals(
          "VaultKvPropertySource[redacted]",
          context.getEnvironment().getPropertySources().get("vault-kv-startup").toString());
    }
  }

  @Test
  void actualVaultAclDeniesOtherRoleWithoutFallback() throws Exception {
    admin("auth/approle/role/denied", Map.of("token_policies", "default"));
    Path role = directory.resolve("denied-role");
    Path secret = directory.resolve("denied-secret");
    Files.writeString(
        role,
        admin("auth/approle/role/denied/role-id", null).path("data").path("role_id").asText());
    Files.writeString(
        secret,
        admin("auth/approle/role/denied/secret-id", Map.of())
            .path("data")
            .path("secret_id")
            .asText());
    MockEnvironment environment =
        new MockEnvironment()
            .withProperty("IAM_VAULT_KV_ENABLED", "true")
            .withProperty("IAM_VAULT_ADDRESS", address())
            .withProperty("IAM_VAULT_ROLE_ID_FILE", role.toString())
            .withProperty("IAM_VAULT_SECRET_ID_FILE", secret.toString())
            .withProperty("IAM_DATABASE_PASSWORD", "local-value");
    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                new VaultKvEnvironmentPostProcessor()
                    .postProcessEnvironment(environment, new SpringApplication()));
    assertEquals("Vault KV startup unavailable.", failure.getMessage());
    assertNull(failure.getCause());
    assertNull(environment.getPropertySources().get("vault-kv-startup"));
  }

  private static JsonNode admin(String path, Object body) throws Exception {
    try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
      var request =
          HttpRequest.newBuilder(URI.create(address() + "/v1/" + path))
              .timeout(Duration.ofSeconds(5))
              .header("X-Vault-Token", ROOT);
      if (body == null) {
        request.GET();
      } else {
        request
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
      }
      var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
      assertTrue(
          response.statusCode() >= 200 && response.statusCode() < 300,
          "Vault test provisioning failed");
      return response.body().isBlank() ? JSON.createObjectNode() : JSON.readTree(response.body());
    }
  }
}

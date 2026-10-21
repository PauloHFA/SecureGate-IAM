package com.securegate.enforcer;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.ConnectionFactory;
import com.securegate.enforcer.bootstrap.VaultKvEnvironmentPostProcessor;
import com.securegate.enforcer.domain.Revocation;
import com.securegate.enforcer.infrastructure.RabbitRevocationWorker;
import com.securegate.enforcer.infrastructure.RedisRevocationStore;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class VaultKvBootstrapIT {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String ROOT = UUID.randomUUID().toString();
  private static final String PASSWORD = UUID.randomUUID().toString();
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
  static final GenericContainer<?> REDIS =
      new GenericContainer<>("redis:7.4.11-alpine")
          .withCommand("redis-server", "--requirepass", PASSWORD)
          .withExposedPorts(6379);

  @Container
  static final GenericContainer<?> RABBIT =
      new GenericContainer<>("rabbitmq:4.3.6-management-alpine")
          .withEnv("RABBITMQ_DEFAULT_USER", "kv_test")
          .withEnv("RABBITMQ_DEFAULT_PASS", PASSWORD)
          .withEnv("RABBITMQ_DEFAULT_VHOST", "securegate")
          .withExposedPorts(5672)
          .waitingFor(Wait.forListeningPort())
          .withStartupTimeout(Duration.ofSeconds(120));

  private static String address() {
    return "http://127.0.0.1:" + VAULT.getMappedPort(8200);
  }

  @BeforeAll
  static void provision() throws Exception {
    // Vault dev already mounts KV v2 at secret/.
    admin("sys/auth/approle", Map.of("type", "approle"));
    admin(
        "sys/policies/acl/securegate-policy-enforcer",
        Map.of("policy", Files.readString(Path.of("../infra/vault/policy-enforcer.hcl"))));
    admin(
        "auth/approle/role/policy-enforcer",
        Map.of("token_policies", "securegate-policy-enforcer", "token_ttl", "1h"));
    Files.writeString(
        directory.resolve("role"),
        admin("auth/approle/role/policy-enforcer/role-id", null)
            .path("data")
            .path("role_id")
            .asText());
    Files.writeString(
        directory.resolve("secret"),
        admin("auth/approle/role/policy-enforcer/secret-id", Map.of())
            .path("data")
            .path("secret_id")
            .asText());
    admin(
        "secret/data/securegate/policy-enforcer",
        Map.of(
            "data",
            Map.of(
                "redis_password",
                PASSWORD,
                "rabbitmq_username",
                "kv_test",
                "rabbitmq_password",
                PASSWORD,
                "redis_host",
                "untrusted-host")));
  }

  @Test
  void discoveredProcessorBootsRealConsumerAndProjectsRevocationUsingOnlyKvCredentials()
      throws Exception {
    try (var context =
        new SpringApplicationBuilder(EnforcerApplication.class)
            .run(
                "--ENFORCER_VAULT_KV_ENABLED=true",
                "--ENFORCER_VAULT_ADDRESS=" + address(),
                "--ENFORCER_VAULT_ROLE_ID_FILE=" + directory.resolve("role"),
                "--ENFORCER_VAULT_SECRET_ID_FILE=" + directory.resolve("secret"),
                "--enforcer.consumer.enabled=true",
                "--enforcer.consumer.host=" + RABBIT.getHost(),
                "--enforcer.consumer.port=" + RABBIT.getMappedPort(5672),
                "--enforcer.consumer.virtual-host=securegate",
                "--enforcer.consumer.username=invalid-local-user",
                "--enforcer.consumer.password=invalid-local-password",
                "--spring.data.redis.host=" + REDIS.getHost(),
                "--spring.data.redis.port=" + REDIS.getMappedPort(6379),
                "--spring.data.redis.password=invalid-local-password")) {
      var redis = context.getBean(StringRedisTemplate.class);
      try (var connection = redis.getConnectionFactory().getConnection()) {
        assertEquals("PONG", connection.ping());
      }
      var worker = context.getBean("revocationWorker", RabbitRevocationWorker.class);
      worker.runOnce(); // synchronous connection/topology before publishing
      var factory = new ConnectionFactory();
      factory.setHost(RABBIT.getHost());
      factory.setPort(RABBIT.getMappedPort(5672));
      factory.setVirtualHost("securegate");
      factory.setUsername("kv_test");
      factory.setPassword(PASSWORD);
      String target = UUID.randomUUID().toString();
      var event =
          TestEvents.token(
              UUID.randomUUID(), "ACCESS_TOKEN", target, Instant.now().plusSeconds(120));
      try (var connection = factory.newConnection();
          var channel = connection.createChannel()) {
        channel.basicPublish(
            "",
            RabbitRevocationWorker.QUEUE,
            TestEvents.properties(event),
            context.getBean(ObjectMapper.class).writeValueAsBytes(event));
      }
      long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
      String key = RedisRevocationStore.key(Revocation.Kind.JTI, target);
      while (!Boolean.TRUE.equals(redis.hasKey(key)) && System.nanoTime() < deadline) {
        worker.runOnce();
        Thread.sleep(100);
      }
      assertTrue(Boolean.TRUE.equals(redis.hasKey(key)));
      assertTrue(
          context
                  .getBean(MeterRegistry.class)
                  .get("iam.enforcer.revocation.messages")
                  .tag("result", "ACK")
                  .counter()
                  .count()
              >= 1);
      assertEquals(
          "VaultKvPropertySource[redacted]",
          context.getEnvironment().getPropertySources().get("vault-kv-startup").toString());
      assertEquals(REDIS.getHost(), context.getEnvironment().getProperty("spring.data.redis.host"));
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
            .withProperty("ENFORCER_VAULT_KV_ENABLED", "true")
            .withProperty("ENFORCER_VAULT_ADDRESS", address())
            .withProperty("ENFORCER_VAULT_ROLE_ID_FILE", role.toString())
            .withProperty("ENFORCER_VAULT_SECRET_ID_FILE", secret.toString())
            .withProperty("spring.data.redis.password", "local-value");
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

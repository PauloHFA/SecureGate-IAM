package com.securegate.iam;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securegate.iam.bootstrap.DatabaseMigration;
import com.securegate.iam.shared.infrastructure.VaultDatabaseDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class VaultDatabaseIT {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String ROOT = UUID.randomUUID().toString();
  private static final String ISSUER_PASSWORD =
      UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
  private static final Network NETWORK = Network.newNetwork();
  @TempDir static Path files;

  @Container
  static final PostgreSQLContainer<?> DATABASE =
      new PostgreSQLContainer<>("postgres:16.15-alpine")
          .withNetwork(NETWORK)
          .withNetworkAliases("postgres")
          .withDatabaseName("dynamic_test")
          .withUsername("migration_owner")
          .withPassword(UUID.randomUUID().toString());

  @Container
  static final GenericContainer<?> VAULT =
      new GenericContainer<>("hashicorp/vault:1.21.4")
          .withNetwork(NETWORK)
          .withEnv("VAULT_DEV_ROOT_TOKEN_ID", ROOT)
          .withEnv("SKIP_SETCAP", "true")
          .withCommand("server", "-dev", "-dev-listen-address=0.0.0.0:8200")
          .withExposedPorts(8200)
          .waitingFor(Wait.forHttp("/v1/sys/health").forStatusCode(200));

  private static String url() {
    return DATABASE.getJdbcUrl().split("\\?")[0];
  }

  private static URI address() {
    return URI.create("http://127.0.0.1:" + VAULT.getMappedPort(8200));
  }

  private static Connection owner() throws SQLException {
    return DriverManager.getConnection(url(), DATABASE.getUsername(), DATABASE.getPassword());
  }

  @BeforeAll
  static void configure() throws Exception {
    DatabaseMigration.run(
        Map.of(
            "IAM_MIGRATION_DATABASE_URL",
            url(),
            "IAM_MIGRATION_DATABASE_USERNAME",
            DATABASE.getUsername(),
            "IAM_MIGRATION_DATABASE_PASSWORD",
            DATABASE.getPassword()));
    try (var connection = owner()) {
      try (var statement =
          connection.prepareStatement(
              "SELECT set_config('securegate.vault_issuer_password',?,false)")) {
        statement.setString(1, ISSUER_PASSWORD);
        statement.execute();
      }
      try (var statement = connection.createStatement()) {
        statement.execute(Files.readString(Path.of("../infra/postgres/vault-issuer.sql")));
      }
    }
    admin("sys/mounts/database", Map.of("type", "database"));
    admin(
        "database/config/securegate-runtime",
        Map.of(
            "plugin_name",
            "postgresql-database-plugin",
            "allowed_roles",
            new String[] {"iam-runtime"},
            "connection_url",
            "postgresql://{{username}}:{{password}}@postgres:5432/dynamic_test?sslmode=disable",
            "username",
            "securegate_vault_database",
            "password",
            ISSUER_PASSWORD,
            "username_template",
            "sg_{{random 20}}",
            "password_authentication",
            "scram-sha-256"));
    role("2m");
    admin("database/rotate-root/securegate-runtime", Map.of());
    admin("sys/auth/approle", Map.of("type", "approle"));
    admin(
        "sys/policies/acl/iam-runtime",
        Map.of("policy", Files.readString(Path.of("../infra/vault/iam-runtime.hcl"))));
    admin(
        "auth/approle/role/iam-runtime",
        Map.of("token_policies", "iam-runtime", "token_ttl", "10m"));
    Files.writeString(
        files.resolve("role"),
        admin("auth/approle/role/iam-runtime/role-id", null).path("data").path("role_id").asText());
    Files.writeString(
        files.resolve("secret"),
        admin("auth/approle/role/iam-runtime/secret-id", Map.of())
            .path("data")
            .path("secret_id")
            .asText());
    admin(
        "secret/data/securegate/iam-runtime",
        Map.of(
            "data",
            Map.of(
                "redis_password",
                UUID.randomUUID().toString(),
                "rabbitmq_username",
                "synthetic-user",
                "rabbitmq_password",
                UUID.randomUUID().toString())));
  }

  private static void role(String ttl) throws Exception {
    var data =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            JSON.readTree(Files.readString(Path.of("../infra/vault/database-role.json")));
    data.put("default_ttl", ttl);
    admin("database/roles/iam-runtime", data);
  }

  private VaultDatabaseDataSource pool() {
    return new VaultDatabaseDataSource(
        url(),
        address(),
        files.resolve("role"),
        files.resolve("secret"),
        new SimpleMeterRegistry());
  }

  @Test
  void runtimeBootsWithNoOwnerCredentialAndCreatesUserOverHttp() throws Exception {
    String token = UUID.randomUUID().toString();
    String username;
    try (var context =
        new SpringApplicationBuilder(IamCoreApplication.class)
            .run(
                "--server.port=0",
                "--IAM_DATABASE_VAULT_ENABLED=true",
                "--IAM_VAULT_KV_ENABLED=true",
                "--spring.flyway.enabled=false",
                "--spring.datasource.url=" + url(),
                "--IAM_DATABASE_USERNAME=invalid-local-user",
                "--IAM_DATABASE_PASSWORD=invalid-local-password",
                "--IAM_VAULT_ADDRESS=" + address(),
                "--IAM_VAULT_ROLE_ID_FILE=" + files.resolve("role"),
                "--IAM_VAULT_SECRET_ID_FILE=" + files.resolve("secret"),
                "--iam.bootstrap.enabled=true",
                "--iam.bootstrap.token=" + token)) {
      var jdbc = context.getBean(JdbcTemplate.class);
      username = jdbc.queryForObject("SELECT current_user", String.class);
      assertTrue(username.matches("sg_[A-Za-z0-9]{20}"));
      assertNull(
          context
              .getEnvironment()
              .getPropertySources()
              .get("vault-kv-startup")
              .getProperty("spring.datasource.password"));
      assertThrows(
          org.springframework.dao.DataAccessException.class,
          () -> jdbc.execute("CREATE TABLE iam_identity.forbidden(id integer)"));
      int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
      try (var http = HttpClient.newHttpClient()) {
        var response =
            http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/admin/users"))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .POST(
                        HttpRequest.BodyPublishers.ofString(
                            JSON.writeValueAsString(
                                Map.of(
                                    "email",
                                    UUID.randomUUID() + "@example.test",
                                    "displayName",
                                    "Dynamic runtime",
                                    "password",
                                    "Synthetic-user-password!"))))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201, response.statusCode(), response.body());
        assertEquals(
            1,
            jdbc.queryForObject(
                "SELECT count(*) FROM iam_audit.outbox_events WHERE event_type='identity.user.created'",
                Integer.class));
      }
    }
    await(() -> !exists(username));
  }

  @Test
  void closingPoolRevokesLoginAndTerminatesBorrowedSession() throws Exception {
    long expiryThreads = expiryThreads();
    var pool = pool();
    try (var connection = pool.getConnection()) {
      String username = currentUser(connection);
      assertEquals("VaultDatabaseDataSource[redacted]", pool.toString());
      pool.close();
      pool.close();
      assertThrows(SQLException.class, pool::getConnection);
      assertThrows(SQLException.class, () -> connection.createStatement().execute("SELECT 1"));
      await(() -> !exists(username));
      await(() -> expiryThreads() <= expiryThreads);
    } finally {
      pool.close();
    }
  }

  @Test
  void expiryClosesExistingSessionsAndRefusesNewConnections() throws Exception {
    role("12s");
    try (var pool = pool();
        var connection = pool.getConnection()) {
      String username = currentUser(connection);
      await(
          () -> {
            try (var ignored = pool.getConnection()) {
              return false;
            } catch (SQLException expired) {
              return true;
            }
          });
      await(
          () -> {
            try (var statement = connection.createStatement()) {
              statement.execute("SELECT 1");
              return false;
            } catch (SQLException closed) {
              return true;
            }
          });
      await(() -> !exists(username));
    } finally {
      role("2m");
    }
  }

  @Test
  void issuerPasswordWasRotatedAndIssuerCannotAssumeRuntimeGroup() throws Exception {
    assertThrows(
        SQLException.class,
        () -> DriverManager.getConnection(url(), "securegate_vault_database", ISSUER_PASSWORD));
    try (var connection = owner();
        var statement = connection.createStatement();
        var rows =
            statement.executeQuery(
                "SELECT inherit_option,set_option,admin_option FROM pg_auth_members WHERE member='securegate_vault_database'::regrole AND roleid='securegate_iam_runtime'::regrole")) {
      assertTrue(rows.next());
      assertFalse(rows.getBoolean(1));
      assertFalse(rows.getBoolean(2));
      assertTrue(rows.getBoolean(3));
    }
  }

  @Test
  void runtimeAppRoleCannotReadOwnerOrConfigureDatabase() throws Exception {
    String token =
        admin(
                "auth/approle/login",
                Map.of(
                    "role_id",
                    Files.readString(files.resolve("role")),
                    "secret_id",
                    Files.readString(files.resolve("secret"))))
            .path("auth")
            .path("client_token")
            .asText();
    try {
      for (String path :
          new String[] {
            "secret/data/securegate/iam-core",
            "database/config/securegate-runtime",
            "database/roles/iam-runtime",
            "database/creds/other-role"
          }) {
        assertEquals(403, send(path, null, token).statusCode());
      }
    } finally {
      assertTrue(send("auth/token/revoke-self", Map.of(), token).statusCode() < 300);
    }
  }

  @Test
  void missingAppRoleFailsWithoutLocalFallback() {
    var failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                new VaultDatabaseDataSource(
                    url(),
                    address(),
                    files.resolve("missing"),
                    files.resolve("secret"),
                    new SimpleMeterRegistry()));
    assertEquals("Vault database unavailable.", failure.getMessage());
    assertNull(failure.getCause());
  }

  @Test
  void packagedMigrationCommandDoesNotStartWebServerAndIsRepeatable() throws Exception {
    Path log = files.resolve("migration.log");
    for (int attempt = 0; attempt < 2; attempt++) {
      var builder =
          new ProcessBuilder(
              Path.of(System.getProperty("java.home"), "bin", "java").toString(),
              "-jar",
              "target/iam-core-0.1.0-SNAPSHOT.jar",
              "--migrate-only");
      builder
          .environment()
          .putAll(
              Map.of(
                  "IAM_MIGRATION_DATABASE_URL",
                  url(),
                  "IAM_MIGRATION_DATABASE_USERNAME",
                  DATABASE.getUsername(),
                  "IAM_MIGRATION_DATABASE_PASSWORD",
                  DATABASE.getPassword()));
      var process = builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
      try {
        assertTrue(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), "Migration process failed");
      } finally {
        process.destroyForcibly();
      }
      String output = Files.readString(log);
      assertFalse(output.contains("Tomcat"));
      assertFalse(output.contains(DATABASE.getPassword()));
    }
  }

  private static String currentUser(Connection connection) throws SQLException {
    try (var statement = connection.createStatement();
        var rows = statement.executeQuery("SELECT current_user")) {
      rows.next();
      return rows.getString(1);
    }
  }

  private static long expiryThreads() {
    return Thread.getAllStackTraces().keySet().stream()
        .filter(thread -> thread.isAlive() && thread.getName().equals("vault-database-expiry"))
        .count();
  }

  private static boolean exists(String username) {
    try (var connection = owner();
        var statement =
            connection.prepareStatement("SELECT count(*) FROM pg_roles WHERE rolname=?")) {
      statement.setString(1, username);
      try (var rows = statement.executeQuery()) {
        rows.next();
        return rows.getInt(1) > 0;
      }
    } catch (SQLException failure) {
      throw new IllegalStateException("Test database unavailable");
    }
  }

  private static void await(BooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() >= deadline) {
        fail("Lease condition was not reached");
      }
      Thread.sleep(100);
    }
  }

  private static JsonNode admin(String path, Object body) throws Exception {
    var response = send(path, body, ROOT);
    assertTrue(
        response.statusCode() >= 200 && response.statusCode() < 300,
        "Vault test provisioning failed: " + path + " (" + response.statusCode() + ")");
    return response.body().isBlank() ? JSON.createObjectNode() : JSON.readTree(response.body());
  }

  private static HttpResponse<String> send(String path, Object body, String token)
      throws Exception {
    try (var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
      var request =
          HttpRequest.newBuilder(address().resolve("/v1/" + path))
              .timeout(Duration.ofSeconds(10))
              .header("X-Vault-Token", token);
      if (body == null) {
        request.GET();
      } else {
        request
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
      }
      return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
  }
}

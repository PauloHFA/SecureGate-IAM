package com.securegate.e2e;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.ConnectionFactory;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * Only disposable infrastructure; processes receive generated credentials through their
 * environment.
 */
final class PropagationEnvironment implements AutoCloseable {
  static final ObjectMapper JSON = new ObjectMapper();
  static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
  private final String password = UUID.randomUUID().toString();
  private final Map<String, String> tracingOverrides;
  private final boolean tracingExport;
  private final boolean structuredLogs;

  PropagationEnvironment() {
    this(Map.of());
  }

  PropagationEnvironment(Map<String, String> tracingOverrides) {
    this(tracingOverrides, false);
  }

  PropagationEnvironment(Map<String, String> tracingOverrides, boolean structuredLogs) {
    this.tracingOverrides = Map.copyOf(tracingOverrides);
    this.tracingExport = !tracingOverrides.isEmpty();
    this.structuredLogs = structuredLogs;
  }

  private final org.testcontainers.containers.Network pamNetwork =
      org.testcontainers.containers.Network.newNetwork();
  final String admin = UUID.randomUUID().toString();
  final PostgreSQLContainer<?> database =
      new PostgreSQLContainer<>("postgres:16.15-alpine")
          .withNetwork(pamNetwork)
          .withNetworkAliases("postgres")
          .withDatabaseName("propagation_test")
          .withUsername("propagation_test")
          .withPassword(password);
  final GenericContainer<?> redisContainer =
      new GenericContainer<>("redis:7.4.11-alpine")
          .withCommand("redis-server", "--requirepass", password)
          .withExposedPorts(6379);
  final GenericContainer<?> rabbit =
      new GenericContainer<>("rabbitmq:4.3.6-management-alpine")
          .withEnv("RABBITMQ_DEFAULT_USER", "propagation_test")
          .withEnv("RABBITMQ_DEFAULT_PASS", password)
          .withEnv("RABBITMQ_DEFAULT_VHOST", "securegate")
          .withExposedPorts(5672)
          .waitingFor(Wait.forListeningPort())
          .withStartupTimeout(Duration.ofSeconds(120));
  final GenericContainer<?> vault =
      new GenericContainer<>("hashicorp/vault:1.21.4")
          .withNetwork(pamNetwork)
          .withEnv("VAULT_DEV_ROOT_TOKEN_ID", password)
          .withEnv("SKIP_SETCAP", "true")
          .withCommand("server", "-dev", "-dev-listen-address=0.0.0.0:8200")
          .withExposedPorts(8200)
          .waitingFor(Wait.forHttp("/v1/sys/health").forStatusCode(200));
  private final List<Process> processes = new ArrayList<>();
  private Process enforcer;
  private Process iam;
  private Map<String, String> iamEnvironment;
  private Map<String, String> enforcerEnvironment;
  private Path credentials;
  private LettuceConnectionFactory redisFactory;
  StringRedisTemplate redis;
  JdbcTemplate jdbc;
  JdbcTemplate runtimeJdbc;
  JdbcTemplate ingestJdbc;
  private boolean pamConfigured;
  URI base;

  long iamProcessId() {
    return iam.pid();
  }

  void enableDependencyMetrics() {
    var root = Path.of("../infra").toAbsolutePath().normalize();
    rabbit
        .withExposedPorts(5672, 15692)
        .withFileSystemBind(
            root.resolve("rabbitmq/rabbitmq.metrics-test.conf").toString(),
            "/etc/rabbitmq/rabbitmq.conf",
            org.testcontainers.containers.BindMode.READ_ONLY)
        .withFileSystemBind(
            root.resolve("rabbitmq/enabled_plugins.metrics").toString(),
            "/etc/rabbitmq/enabled_plugins",
            org.testcontainers.containers.BindMode.READ_ONLY);
    vault
        .withFileSystemBind(
            root.resolve("vault/metrics-listener.hcl").toString(),
            "/vault/config/metrics-listener.hcl",
            org.testcontainers.containers.BindMode.READ_ONLY)
        .withCommand("server", "-dev", "-dev-listen-address=127.0.0.1:18200");
  }

  String provisionVaultMetricsToken() throws Exception {
    vault(
        "sys/policies/acl/securegate-metrics",
        Map.of("policy", Files.readString(Path.of("../infra/vault/metrics.hcl"))));
    return vault(
            "auth/token/create-orphan",
            Map.of(
                "policies",
                List.of("securegate-metrics"),
                "no_default_policy",
                true,
                "ttl",
                "30m",
                "renewable",
                false))
        .path("auth")
        .path("client_token")
        .asText();
  }

  void start() throws Exception {
    database.start();
    redisContainer.start();
    rabbit.start();
    vault.start();
    credentials = Files.createTempDirectory("securegate-propagation-");
    vault("sys/mounts/transit", Map.of("type", "transit"));
    vault("transit/keys/securegate-jwt", Map.of("type", "rsa-3072", "exportable", false));
    vault("transit/keys/securegate-mfa", Map.of("type", "aes256-gcm96", "exportable", false));
    vault("sys/auth/approle", Map.of("type", "approle"));
    vault(
        "sys/policies/acl/iam-test",
        Map.of("policy", Files.readString(Path.of("../infra/vault/iam-core.hcl"))));
    vault(
        "auth/approle/role/iam-test",
        Map.of("token_policies", List.of("iam-test"), "token_ttl", "1h"));
    Files.writeString(
        credentials.resolve("role"),
        vault("auth/approle/role/iam-test/role-id", null).path("data").path("role_id").asText());
    Files.writeString(
        credentials.resolve("secret"),
        vault("auth/approle/role/iam-test/secret-id", Map.of())
            .path("data")
            .path("secret_id")
            .asText());
    var server =
        new RedisStandaloneConfiguration(
            redisContainer.getHost(), redisContainer.getMappedPort(6379));
    server.setPassword(password);
    redisFactory = new LettuceConnectionFactory(server);
    redisFactory.afterPropertiesSet();
    redisFactory.start();
    redis = new StringRedisTemplate(redisFactory);
    jdbc =
        new JdbcTemplate(
            new DriverManagerDataSource(database.getJdbcUrl(), database.getUsername(), password));
    launch(
        "policy-enforcer",
        Map.ofEntries(
            Map.entry("ENFORCER_CONSUMER_ENABLED", "true"),
            Map.entry("ENFORCER_RABBITMQ_HOST", rabbit.getHost()),
            Map.entry("ENFORCER_RABBITMQ_PORT", rabbit.getMappedPort(5672).toString()),
            Map.entry("ENFORCER_RABBITMQ_USERNAME", "propagation_test"),
            Map.entry("ENFORCER_RABBITMQ_PASSWORD", password),
            Map.entry("ENFORCER_REDIS_HOST", redisContainer.getHost()),
            Map.entry("ENFORCER_REDIS_PORT", redisContainer.getMappedPort(6379).toString()),
            Map.entry("ENFORCER_REDIS_PASSWORD", password)));
    // Worker uses basicGet polling, not a long-lived basicConsume registration.
    await(
        "enforcer topology",
        Duration.ofSeconds(45),
        () -> {
          var factory = new ConnectionFactory();
          factory.setHost(rabbit.getHost());
          factory.setPort(rabbit.getMappedPort(5672));
          factory.setUsername("propagation_test");
          factory.setPassword(password);
          factory.setVirtualHost("securegate");
          factory.setConnectionTimeout(2000);
          factory.setHandshakeTimeout(2000);
          factory.setChannelRpcTimeout(2000);
          factory.setAutomaticRecoveryEnabled(false);
          try (var connection = factory.newConnection();
              var channel = connection.createChannel()) {
            channel.queueDeclarePassive("enforcer.revocation");
            channel.queueDeclarePassive("enforcer.block-policy");
            return true;
          }
        });
    int port;
    try (var socket = new ServerSocket(0, 0, java.net.InetAddress.getByName("127.0.0.1"))) {
      port = socket.getLocalPort();
    }
    base = URI.create("http://127.0.0.1:" + port);
    launch(
        "iam-core",
        Map.ofEntries(
            Map.entry("IAM_PORT", Integer.toString(port)),
            Map.entry("IAM_BIND_ADDRESS", "127.0.0.1"),
            Map.entry("IAM_DATABASE_URL", database.getJdbcUrl()),
            Map.entry("IAM_DATABASE_USERNAME", database.getUsername()),
            Map.entry("IAM_DATABASE_PASSWORD", password),
            Map.entry("IAM_BOOTSTRAP_ENABLED", "true"),
            Map.entry("IAM_BOOTSTRAP_TOKEN", admin),
            Map.entry("IAM_TRANSIT_ENABLED", "true"),
            Map.entry("IAM_AUTHORIZATION_ENABLED", "true"),
            Map.entry("IAM_ISSUER", base.toString()),
            Map.entry("IAM_VAULT_ADDRESS", vaultAddress()),
            Map.entry("IAM_VAULT_ROLE_ID_FILE", credentials.resolve("role").toString()),
            Map.entry("IAM_VAULT_SECRET_ID_FILE", credentials.resolve("secret").toString()),
            Map.entry("IAM_RESOURCE_ENFORCEMENT_ENABLED", "true"),
            Map.entry("IAM_ENFORCEMENT_REDIS_HOST", redisContainer.getHost()),
            Map.entry("IAM_ENFORCEMENT_REDIS_PORT", redisContainer.getMappedPort(6379).toString()),
            Map.entry("IAM_ENFORCEMENT_REDIS_PASSWORD", password),
            Map.entry("IAM_OUTBOX_PUBLISHER_ENABLED", "true"),
            Map.entry("IAM_RABBITMQ_HOST", rabbit.getHost()),
            Map.entry("IAM_RABBITMQ_PORT", rabbit.getMappedPort(5672).toString()),
            Map.entry("IAM_RABBITMQ_USERNAME", "propagation_test"),
            Map.entry("IAM_RABBITMQ_PASSWORD", password)));
    await(
        "IAM readiness",
        Duration.ofSeconds(60),
        () ->
            HTTP.send(
                        HttpRequest.newBuilder(base.resolve("/actuator/health/readiness"))
                            .timeout(Duration.ofSeconds(2))
                            .GET()
                            .build(),
                        HttpResponse.BodyHandlers.discarding())
                    .statusCode()
                == 200);
  }

  private void launch(String module, Map<String, String> environment) throws Exception {
    var effectiveEnvironment = new java.util.HashMap<>(environment);
    effectiveEnvironment.putAll(tracingOverrides);
    if (tracingExport) {
      effectiveEnvironment.put(
          "SPRING_PROFILES_ACTIVE",
          (structuredLogs ? "structured-logs," : "")
              + (module.equals("iam-core") ? "http-tracing" : "consumer-tracing"));
      effectiveEnvironment.put(
          module.equals("iam-core")
              ? "IAM_HTTP_TRACE_PROBABILITY"
              : "ENFORCER_TRACING_SAMPLING_PROBABILITY",
          "1.0");
    }
    var jar =
        Path.of(
                "..",
                module,
                "target",
                module + "-" + System.getProperty("securegate.version") + ".jar")
            .toAbsolutePath()
            .normalize();
    assertTrue(Files.isRegularFile(jar), "Build application JARs with root Maven verify first");
    var java =
        Path.of(
            System.getProperty("java.home"),
            "bin",
            System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
    var builder = new ProcessBuilder(java.toString(), "-Xmx256m", "-jar", jar.toString());
    builder
        .environment()
        .keySet()
        .removeIf(
            key ->
                key.startsWith("IAM_")
                    || key.startsWith("ENFORCER_")
                    || key.startsWith("SPRING_")
                    || key.startsWith("LOGGING_")
                    || key.startsWith("SERVER_")
                    || key.startsWith("MANAGEMENT_")
                    || key.equals("DEBUG")
                    || key.equals("TRACE")
                    || key.equals("JAVA_TOOL_OPTIONS")
                    || key.equals("JDK_JAVA_OPTIONS"));
    builder.environment().putAll(effectiveEnvironment);
    Files.createDirectories(Path.of("target/e2e"));
    builder
        .redirectErrorStream(true)
        .redirectOutput(
            ProcessBuilder.Redirect.appendTo(Path.of("target/e2e", module + ".log").toFile()));
    var process = builder.start();
    processes.add(process);
    if (module.equals("policy-enforcer")) {
      enforcer = process;
      enforcerEnvironment = Map.copyOf(effectiveEnvironment);
    }
    if (module.equals("iam-core")) {
      iam = process;
      iamEnvironment = Map.copyOf(effectiveEnvironment);
    }
  }

  long iamPid() {
    return iam.pid();
  }

  void stopIam() throws Exception {
    iam.destroy();
    if (!iam.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
      iam.destroyForcibly();
      assertTrue(iam.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
    }
    assertFalse(iam.isAlive());
  }

  void startIam(Map<String, String> overrides) throws Exception {
    assertFalse(iam.isAlive());
    var environment = new java.util.HashMap<>(iamEnvironment);
    environment.putAll(overrides);
    launch("iam-core", environment);
    await(
        "restarted IAM readiness",
        Duration.ofSeconds(60),
        () ->
            HTTP.send(
                        HttpRequest.newBuilder(base.resolve("/actuator/health/readiness"))
                            .timeout(Duration.ofSeconds(2))
                            .GET()
                            .build(),
                        HttpResponse.BodyHandlers.discarding())
                    .statusCode()
                == 200);
  }

  /** Audit-only disposable setup; does not enable PAM or touch a developer's stack. */
  void enableAuditJourney() throws Exception {
    stopIam();
    String ingest = randomHex(), maintenance = randomHex();
    try (var connection =
        java.sql.DriverManager.getConnection(
            database.getJdbcUrl(), database.getUsername(), password)) {
      try (var configure =
          connection.prepareStatement(
              "SELECT set_config('securegate.securegate_audit_ingest_password',?,false),set_config('securegate.securegate_audit_maintenance_password',?,false)")) {
        configure.setString(1, ingest);
        configure.setString(2, maintenance);
        configure.execute();
      }
      try (var statement = connection.createStatement()) {
        statement.execute(Files.readString(Path.of("../infra/postgres/audit-privileges.sql")));
      }
    }
    Files.writeString(credentials.resolve("audit-ingest"), ingest);
    startIam(
        Map.of(
            "IAM_AUDIT_INGESTION_ENABLED", "true",
            "IAM_AUDIT_CONSUMER_ENABLED", "true",
            "IAM_AUDIT_QUERY_ENABLED", "true",
            "IAM_AUDIT_DATABASE_URL", database.getJdbcUrl().split("\\?", 2)[0],
            "IAM_AUDIT_INGEST_USERNAME", "securegate_audit_ingest_login",
            "IAM_AUDIT_INGEST_PASSWORD_FILE", credentials.resolve("audit-ingest").toString()));
  }

  void enablePamJourney() throws Exception {
    if (pamConfigured) {
      return;
    }
    stopIam();
    String issuer = randomHex(),
        runtime = randomHex(),
        ingest = randomHex(),
        maintenance = randomHex();
    try (var connection =
        java.sql.DriverManager.getConnection(
            database.getJdbcUrl(), database.getUsername(), password)) {
      try (var configure =
          connection.prepareStatement(
              "SELECT set_config('securegate.pam_issuer_password',?,false),set_config('securegate.securegate_audit_ingest_password',?,false),set_config('securegate.securegate_audit_maintenance_password',?,false)")) {
        configure.setString(1, issuer);
        configure.setString(2, ingest);
        configure.setString(3, maintenance);
        configure.execute();
      }
      try (var statement = connection.createStatement()) {
        statement.execute(Files.readString(Path.of("../infra/postgres/runtime-privileges.sql")));
        statement.execute(Files.readString(Path.of("../infra/postgres/pam-diagnostics.sql")));
        statement.execute(Files.readString(Path.of("../infra/postgres/audit-privileges.sql")));
        statement.execute(
            "CREATE ROLE synthetic_pam_runtime LOGIN INHERIT PASSWORD '" + runtime + "'");
        statement.execute("GRANT securegate_iam_runtime TO synthetic_pam_runtime");
      }
    }
    runtimeJdbc =
        new JdbcTemplate(
            new DriverManagerDataSource(database.getJdbcUrl(), "synthetic_pam_runtime", runtime));
    ingestJdbc =
        new JdbcTemplate(
            new DriverManagerDataSource(
                database.getJdbcUrl(), "securegate_audit_ingest_login", ingest));
    vault("sys/mounts/pam-database", Map.of("type", "database"));
    vault(
        "pam-database/config/securegate-pam",
        Map.of(
            "plugin_name",
            "postgresql-database-plugin",
            "allowed_roles",
            List.of(
                "diagnostics-10",
                "diagnostics-30",
                "diagnostics-60",
                "diagnostics-120",
                "diagnostics-240"),
            "connection_url",
            "postgresql://{{username}}:{{password}}@postgres:5432/propagation_test?sslmode=disable",
            "username",
            "securegate_vault_pam",
            "password",
            issuer,
            "username_template",
            "pam_{{random 20}}",
            "password_authentication",
            "scram-sha-256"));
    for (int ttl : new int[] {10, 30, 60, 120, 240}) {
      var role =
          (com.fasterxml.jackson.databind.node.ObjectNode)
              JSON.readTree(
                  Files.readString(Path.of("../infra/vault/pam-role.json"))
                      .replace("PAM_TTL_SECONDS", Integer.toString(ttl)));
      role.put("default_ttl", ttl + "s");
      role.put("max_ttl", ttl + "s");
      vault("pam-database/roles/diagnostics-" + ttl, role);
    }
    vault("pam-database/rotate-root/securegate-pam", Map.of());
    vault(
        "sys/policies/acl/iam-pam",
        Map.of("policy", Files.readString(Path.of("../infra/vault/iam-pam.hcl"))));
    vault(
        "auth/approle/role/iam-pam",
        Map.of("token_policies", "iam-pam", "token_ttl", "5m", "token_max_ttl", "5m"));
    Files.writeString(
        credentials.resolve("pam-role"),
        vault("auth/approle/role/iam-pam/role-id", null).path("data").path("role_id").asText());
    Files.writeString(
        credentials.resolve("pam-secret"),
        vault("auth/approle/role/iam-pam/secret-id", Map.of())
            .path("data")
            .path("secret_id")
            .asText());
    Files.writeString(credentials.resolve("audit-ingest"), ingest);
    startIam(
        Map.ofEntries(
            Map.entry("IAM_DATABASE_USERNAME", "synthetic_pam_runtime"),
            Map.entry("IAM_DATABASE_PASSWORD", runtime),
            Map.entry("SPRING_FLYWAY_ENABLED", "false"),
            Map.entry("IAM_PAM_ENABLED", "true"),
            Map.entry("IAM_PAM_CREDENTIALS_ENABLED", "true"),
            Map.entry("IAM_PAM_VAULT_ADDRESS", vaultAddress()),
            Map.entry("IAM_PAM_VAULT_ROLE_ID_FILE", credentials.resolve("pam-role").toString()),
            Map.entry("IAM_PAM_VAULT_SECRET_ID_FILE", credentials.resolve("pam-secret").toString()),
            Map.entry("IAM_AUDIT_INGESTION_ENABLED", "true"),
            Map.entry("IAM_AUDIT_CONSUMER_ENABLED", "true"),
            Map.entry("IAM_AUDIT_QUERY_ENABLED", "true"),
            Map.entry("IAM_AUDIT_DATABASE_URL", database.getJdbcUrl().split("\\?", 2)[0]),
            Map.entry("IAM_AUDIT_INGEST_USERNAME", "securegate_audit_ingest_login"),
            Map.entry(
                "IAM_AUDIT_INGEST_PASSWORD_FILE", credentials.resolve("audit-ingest").toString())));
    pamConfigured = true;
  }

  private static String randomHex() {
    return UUID.randomUUID().toString().replace("-", "")
        + UUID.randomUUID().toString().replace("-", "");
  }

  void pauseVault(boolean paused) {
    if (paused) {
      vault.getDockerClient().pauseContainerCmd(vault.getContainerId()).exec();
    } else {
      vault.getDockerClient().unpauseContainerCmd(vault.getContainerId()).exec();
    }
  }

  void stopEnforcer() throws Exception {
    enforcer.destroy();
    if (!enforcer.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
      enforcer.destroyForcibly();
      assertTrue(enforcer.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
    }
    assertFalse(enforcer.isAlive());
  }

  void restartEnforcer(Map<String, String> overrides) throws Exception {
    stopEnforcer();
    var environment = new java.util.HashMap<>(enforcerEnvironment);
    environment.putAll(overrides);
    launch("policy-enforcer", environment);
  }

  com.rabbitmq.client.Connection rabbitConnection() throws Exception {
    var factory = new ConnectionFactory();
    factory.setHost(rabbit.getHost());
    factory.setPort(rabbit.getMappedPort(5672));
    factory.setUsername("propagation_test");
    factory.setPassword(password);
    factory.setVirtualHost("securegate");
    factory.setConnectionTimeout(2000);
    factory.setHandshakeTimeout(2000);
    factory.setChannelRpcTimeout(2000);
    factory.setAutomaticRecoveryEnabled(false);
    return factory.newConnection();
  }

  record Wire(com.rabbitmq.client.AMQP.BasicProperties properties, byte[] body) {
    @Override
    public String toString() {
      return "Wire[REDACTED]";
    }
  }

  Wire capture(String eventId) throws Exception {
    // Only the disposable audit queue is drained, never an enforcer queue.
    try (var connection = rabbitConnection();
        var channel = connection.createChannel()) {
      for (int i = 0; i < 1000; i++) {
        var message = channel.basicGet("audit.persist", true);
        assertNotNull(message, "Published event must have reached the disposable audit queue");
        if (eventId.equals(message.getProps().getMessageId())) {
          return new Wire(message.getProps(), message.getBody());
        }
      }
    }
    throw new AssertionError("Event not found in disposable audit queue");
  }

  void replay(Wire wire, int times) throws Exception {
    try (var connection = rabbitConnection();
        var channel = connection.createChannel()) {
      channel.confirmSelect();
      for (int i = 0; i < times; i++) {
        channel.basicPublish(
            "security.events", wire.properties().getType(), wire.properties(), wire.body());
      }
      channel.waitForConfirmsOrDie(2000);
    }
  }

  void brokerApplication(boolean start) throws Exception {
    assertEquals(
        0, rabbit.execInContainer("rabbitmqctl", start ? "start_app" : "stop_app").getExitCode());
  }

  void pauseRedis(boolean pause) {
    var docker = org.testcontainers.DockerClientFactory.instance().client();
    if (pause) {
      docker.pauseContainerCmd(redisContainer.getContainerId()).exec();
    } else {
      docker.unpauseContainerCmd(redisContainer.getContainerId()).exec();
    }
  }

  private String vaultAddress() {
    return "http://" + vault.getHost() + ":" + vault.getMappedPort(8200);
  }

  private JsonNode vault(String path, Object body) throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create(vaultAddress() + "/v1/" + path))
            .timeout(Duration.ofSeconds(5))
            .header("X-Vault-Token", password)
            .header("Content-Type", "application/json");
    if (body == null) {
      request.GET();
    } else {
      request.POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
    }
    var response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    assertTrue(response.statusCode() < 300, "Vault fixture setup failed");
    return response.body().isBlank() ? JSON.createObjectNode() : JSON.readTree(response.body());
  }

  static void await(String description, Duration timeout, Check check) throws Exception {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      try {
        if (check.ready()) {
          return;
        }
      } catch (Exception transientFailure) {
        /* Bounded retry; never an assertion success. */
      }
      Thread.sleep(25);
    }
    fail("Timed out: " + description + "; inspect target/e2e application logs");
  }

  @FunctionalInterface
  interface Check {
    boolean ready() throws Exception;
  }

  @Override
  public void close() throws Exception {
    for (var process : processes.reversed()) {
      process.destroy();
      if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
        process.destroyForcibly();
        process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
      }
    }
    if (redisFactory != null) {
      redisFactory.destroy();
    }
    vault.stop();
    rabbit.stop();
    redisContainer.stop();
    database.stop();
    pamNetwork.close();
    if (credentials != null) {
      Files.deleteIfExists(credentials.resolve("role"));
      Files.deleteIfExists(credentials.resolve("secret"));
      Files.deleteIfExists(credentials.resolve("pam-role"));
      Files.deleteIfExists(credentials.resolve("pam-secret"));
      Files.deleteIfExists(credentials.resolve("audit-ingest"));
      Files.deleteIfExists(credentials);
    }
  }
}

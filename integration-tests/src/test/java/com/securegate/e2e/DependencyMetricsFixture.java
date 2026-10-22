package com.securegate.e2e;

import static org.junit.jupiter.api.Assertions.*;

import com.github.dockerjava.api.model.AccessMode;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Volume;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.data.redis.core.RedisCallback;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/** Four real, simultaneous dependency metrics endpoints; only disposable business containers. */
final class DependencyMetricsFixture implements AutoCloseable {
  private final PropagationEnvironment environment;
  private final Path temporary;
  private GenericContainer<?> postgresExporter;
  private GenericContainer<?> redisExporter;
  private Path vaultTokenFile;

  DependencyMetricsFixture(PropagationEnvironment environment, Path temporary) {
    this.environment = environment;
    this.temporary = temporary;
  }

  void start() throws Exception {
    String postgresPassword = randomHex();
    Path postgresFile = temporary.resolve("postgres-metrics-password");
    Files.writeString(postgresFile, postgresPassword);
    environment.jdbc.execute(
        "CREATE ROLE securegate_metrics LOGIN PASSWORD '"
            + postgresPassword
            + "' CONNECTION LIMIT 3");
    environment.jdbc.execute("GRANT pg_monitor TO securegate_metrics");
    environment.jdbc.execute("GRANT CONNECT ON DATABASE propagation_test TO securegate_metrics");
    environment.jdbc.execute(
        "ALTER ROLE securegate_metrics SET default_transaction_read_only = on");

    String redisPassword = randomHex();
    String redisTarget =
        "redis://host.docker.internal:" + environment.redisContainer.getMappedPort(6379);
    byte[][] acl =
        List.of(
                "SETUSER",
                "securegate_metrics",
                "reset",
                "on",
                ">" + redisPassword,
                "resetkeys",
                "resetchannels",
                "+info",
                "+ping",
                "+client|setname",
                "+command|info",
                "+slowlog|len")
            .stream()
            .map(value -> value.getBytes(StandardCharsets.UTF_8))
            .toArray(byte[][]::new);
    environment.redis.execute((RedisCallback<Object>) connection -> connection.execute("ACL", acl));
    Path redisFile = temporary.resolve("redis-metrics-password.json");
    Files.writeString(
        redisFile,
        PropagationEnvironment.JSON.writeValueAsString(
            Map.of(
                "redis://securegate_metrics@host.docker.internal:"
                    + environment.redisContainer.getMappedPort(6379),
                redisPassword)));

    String vaultToken = environment.provisionVaultMetricsToken();
    assertFalse(vaultToken.isBlank());
    vaultTokenFile = temporary.resolve("vault-metrics-token");
    Files.writeString(vaultTokenFile, vaultToken);
    URI vaultMetrics =
        URI.create(
            "http://127.0.0.1:"
                + environment.vault.getMappedPort(8200)
                + "/v1/sys/metrics?format=prometheus");
    assertEquals(403, vaultMetrics(vaultMetrics, null).statusCode());
    assertEquals(200, vaultMetrics(vaultMetrics, vaultToken).statusCode());

    postgresExporter =
        new GenericContainer<>(
                image(
                    "docker-compose.postgres-metrics.yml",
                    "quay.io/prometheuscommunity/postgres-exporter:"))
            .withExtraHost("host.docker.internal", "host-gateway")
            .withExposedPorts(9187)
            .withEnv(
                "DATA_SOURCE_URI",
                "host.docker.internal:"
                    + environment.database.getMappedPort(5432)
                    + "/propagation_test?sslmode=disable&connect_timeout=2")
            .withEnv("DATA_SOURCE_USER", "securegate_metrics")
            .withEnv("DATA_SOURCE_PASS_FILE", "/run/secrets/postgres-metrics-password")
            .withCommand(
                "--collection-timeout=2s",
                "--disable-default-metrics",
                "--disable-settings-metrics",
                "--collector.database",
                "--collector.stat_database",
                "--collector.stat_bgwriter",
                "--no-collector.locks",
                "--no-collector.replication",
                "--no-collector.replication_slot",
                "--no-collector.roles",
                "--no-collector.stat_progress_vacuum",
                "--no-collector.stat_user_tables",
                "--no-collector.statio_user_tables",
                "--no-collector.stat_statements")
            .withCreateContainerCmdModifier(
                cmd ->
                    cmd.getHostConfig()
                        .withBinds(
                            new Bind(
                                postgresFile.toString(),
                                new Volume("/run/secrets/postgres-metrics-password"),
                                AccessMode.ro)))
            .waitingFor(Wait.forHttp("/metrics").forPort(9187))
            .withStartupTimeout(Duration.ofSeconds(90));
    postgresExporter.start();
    redisExporter =
        new GenericContainer<>(
                image("docker-compose.redis-metrics.yml", "oliver006/redis_exporter:"))
            .withExtraHost("host.docker.internal", "host-gateway")
            .withExposedPorts(9121)
            .withEnv("REDIS_ADDR", redisTarget)
            .withEnv("REDIS_USER", "securegate_metrics")
            .withEnv("REDIS_PASSWORD_FILE", "/run/secrets/redis-metrics-password.json")
            .withCommand(
                "--config-command=-",
                "--connection-timeout=2s",
                "--set-client-name=false",
                "--exclude-latency-histogram-metrics",
                "--disable-exporting-key-values",
                "--disable-scrape-endpoint")
            .withCreateContainerCmdModifier(
                cmd ->
                    cmd.getHostConfig()
                        .withBinds(
                            new Bind(
                                redisFile.toString(),
                                new Volume("/run/secrets/redis-metrics-password.json"),
                                AccessMode.ro)))
            .waitingFor(Wait.forHttp("/metrics").forPort(9121))
            .withStartupTimeout(Duration.ofSeconds(90));
    redisExporter.start();
  }

  Path vaultTokenFile() {
    return vaultTokenFile;
  }

  String prometheusJobs() throws Exception {
    Path root = TracingBackendIT.ROOT;
    return Files.readString(root.resolve("postgres-scrape.template.yml"))
            .replace("__POSTGRES_METRICS_PORT__", postgresExporter.getMappedPort(9187).toString())
        + Files.readString(root.resolve("redis-scrape.template.yml"))
            .replace("__REDIS_METRICS_PORT__", redisExporter.getMappedPort(9121).toString())
        + Files.readString(root.resolve("rabbitmq-scrape.template.yml"))
            .replace(
                "__RABBITMQ_METRICS_PORT__", environment.rabbit.getMappedPort(15692).toString())
        + Files.readString(root.resolve("vault-scrape.template.yml"))
            .replace("__VAULT_PORT__", environment.vault.getMappedPort(8200).toString());
  }

  private static HttpResponse<String> vaultMetrics(URI uri, String token) throws Exception {
    var builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5));
    if (token != null) builder.header("X-Vault-Token", token);
    return PropagationEnvironment.HTTP.send(
        builder.GET().build(), HttpResponse.BodyHandlers.ofString());
  }

  private static String image(String composeFile, String prefix) throws Exception {
    var text = Files.readString(Path.of("../infra", composeFile));
    var matcher = Pattern.compile("image: (" + Pattern.quote(prefix) + "[^\\s]+)").matcher(text);
    assertTrue(matcher.find(), "Versioned exporter image required");
    assertTrue(matcher.group(1).contains("@sha256:"));
    return matcher.group(1);
  }

  private static String randomHex() {
    return UUID.randomUUID().toString().replace("-", "")
        + UUID.randomUUID().toString().replace("-", "");
  }

  @Override
  public void close() {
    if (redisExporter != null) redisExporter.stop();
    if (postgresExporter != null) postgresExporter.stop();
  }
}

package com.securegate.iam.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/** One startup snapshot, before DataSource and clients are created. No runtime refresh. */
public final class VaultKvEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Duration TIMEOUT = Duration.ofSeconds(2);
  private static final String KV_PATH = "secret/data/securegate/iam-core";

  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE;
  }

  @Override
  public void postProcessEnvironment(
      ConfigurableEnvironment environment, SpringApplication application) {
    String enabled = environment.getProperty("IAM_VAULT_KV_ENABLED", "false");
    if ("false".equalsIgnoreCase(enabled)) {
      return;
    }
    if (!"true".equalsIgnoreCase(enabled)) {
      throw unavailable();
    }
    try {
      URI address =
          URI.create(environment.getProperty("IAM_VAULT_ADDRESS", "http://127.0.0.1:8200"));
      boolean local =
          "127.0.0.1".equals(address.getHost()) || "localhost".equals(address.getHost());
      if (address.getHost() == null
          || address.getPort() > 65535
          || !("https".equals(address.getScheme()) || (local && "http".equals(address.getScheme())))
          || address.getUserInfo() != null
          || address.getQuery() != null
          || address.getFragment() != null
          || !(address.getPath().isEmpty() || "/".equals(address.getPath()))) {
        throw unavailable();
      }
      String role =
          credential(environment, "IAM_VAULT_ROLE_ID_FILE", "infra/.local/iam-core-role-id");
      String secret =
          credential(environment, "IAM_VAULT_SECRET_ID_FILE", "infra/.local/iam-core-secret-id");
      try (HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build()) {
        JsonNode auth =
            request(
                    http,
                    address,
                    "auth/approle/login",
                    Map.of("role_id", role, "secret_id", secret),
                    null)
                .path("auth");
        String token = text(auth, "client_token");
        try {
          if (auth.path("lease_duration").asLong() < 10) {
            throw unavailable();
          }
          JsonNode data = request(http, address, KV_PATH, null, token).path("data");
          JsonNode metadata = data.path("metadata");
          if (metadata.path("version").asLong() < 1
              || !metadata.path("destroyed").isBoolean()
              || metadata.path("destroyed").asBoolean()
              || !metadata.path("deletion_time").isTextual()
              || !metadata.path("deletion_time").asText().isEmpty()) {
            throw unavailable();
          }
          JsonNode values = data.path("data");
          Map<String, Object> properties = new LinkedHashMap<>();
          properties.put("spring.datasource.username", text(values, "postgres_username"));
          properties.put("spring.datasource.password", text(values, "postgres_password"));
          properties.put("iam.resources.enforcement.password", text(values, "redis_password"));
          for (String prefix : new String[] {"iam.outbox.publisher.", "iam.audit.consumer."}) {
            properties.put(prefix + "username", text(values, "rabbitmq_username"));
            properties.put(prefix + "password", text(values, "rabbitmq_password"));
          }
          // Publish only after the complete snapshot has been validated and token revoked.
          request(http, address, "auth/token/revoke-self", Map.of(), token);
          token = null;
          environment
              .getPropertySources()
              .addFirst(
                  new MapPropertySource("vault-kv-startup", properties) {
                    @Override
                    public String toString() {
                      return "VaultKvPropertySource[redacted]";
                    }
                  });
        } finally {
          if (token != null) {
            request(http, address, "auth/token/revoke-self", Map.of(), token);
          }
        }
      }
    } catch (Exception failure) {
      // Do not attach HTTP responses, credentials, filesystem paths or parser causes.
      if (failure instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw unavailable();
    }
  }

  private static String credential(ConfigurableEnvironment environment, String key, String fallback)
      throws Exception {
    Path path = Path.of(environment.getProperty(key, fallback));
    if (Files.size(path) > 4096) {
      throw unavailable();
    }
    String value = Files.readString(path).strip();
    if (value.isBlank()
        || value.length() > 1024
        || value.chars().anyMatch(Character::isISOControl)) {
      throw unavailable();
    }
    return value;
  }

  private static String text(JsonNode data, String key) {
    JsonNode value = data.path(key);
    if (!value.isTextual()
        || value.asText().isBlank()
        || value.asText().length() > 4096
        || value.asText().chars().anyMatch(Character::isISOControl)) {
      throw unavailable();
    }
    return value.asText();
  }

  private static JsonNode request(
      HttpClient http, URI address, String path, Object body, String token) throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(address.resolve("/v1/" + path))
            .timeout(TIMEOUT)
            .header("Accept", "application/json");
    if (token != null) {
      builder.header("X-Vault-Token", token);
    }
    if (body == null) {
      builder.GET();
    } else {
      builder
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
    }
    HttpResponse<byte[]> response =
        http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw unavailable();
    }
    byte[] bytes = response.body();
    if (bytes.length > 65536) {
      throw unavailable();
    }
    return bytes.length == 0 ? JSON.createObjectNode() : JSON.readTree(bytes);
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException("Vault KV startup unavailable.");
  }
}

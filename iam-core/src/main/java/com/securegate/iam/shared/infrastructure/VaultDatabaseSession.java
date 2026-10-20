package com.securegate.iam.shared.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

final class VaultDatabaseSession implements AutoCloseable {
  private static final ObjectMapper JSON = new ObjectMapper();
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
  private final URI address;
  private String token;
  private long deadline;

  VaultDatabaseSession(URI address, Path roleFile, Path secretFile) {
    this.address = address;
    try {
      boolean local =
          address != null
              && ("127.0.0.1".equals(address.getHost()) || "localhost".equals(address.getHost()));
      if (address == null
          || address.getHost() == null
          || address.getPort() > 65535
          || !("https".equals(address.getScheme()) || (local && "http".equals(address.getScheme())))
          || address.getUserInfo() != null
          || address.getQuery() != null
          || address.getFragment() != null
          || !(address.getPath().isEmpty() || "/".equals(address.getPath()))) {
        throw unavailable();
      }
      long started = System.nanoTime();
      JsonNode auth =
          request(
                  "auth/approle/login",
                  Map.of("role_id", file(roleFile), "secret_id", file(secretFile)))
              .path("auth");
      token = text(auth, "client_token");
      long ttl = auth.path("lease_duration").asLong();
      if (ttl < 10 || ttl > 86400) {
        throw unavailable();
      }
      deadline = started + Duration.ofSeconds(ttl).toNanos();
    } catch (Exception failure) {
      close();
      throw unavailable();
    }
  }

  long deadline() {
    return deadline;
  }

  JsonNode request(String path, Object body) {
    try {
      var builder =
          HttpRequest.newBuilder(address.resolve("/v1/" + path))
              .timeout(Duration.ofSeconds(2))
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
      var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
      if (response.statusCode() < 200
          || response.statusCode() >= 300
          || response.body().length > 65536) {
        throw unavailable();
      }
      return response.body().length == 0 ? JSON.createObjectNode() : JSON.readTree(response.body());
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw unavailable();
    } catch (Exception failure) {
      throw unavailable();
    }
  }

  static String text(JsonNode node, String field) {
    JsonNode value = node.path(field);
    if (!value.isTextual()
        || value.asText().isBlank()
        || value.asText().length() > 4096
        || value.asText().chars().anyMatch(Character::isISOControl)) {
      throw unavailable();
    }
    return value.asText();
  }

  private static String file(Path path) throws Exception {
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

  @Override
  public void close() {
    try {
      if (token != null) {
        request("auth/token/revoke-self", Map.of());
      }
    } finally {
      token = null;
      http.close();
    }
  }

  static IllegalStateException unavailable() {
    return new IllegalStateException("Vault database unavailable.");
  }

  @Override
  public String toString() {
    return "VaultDatabaseSession[redacted]";
  }
}

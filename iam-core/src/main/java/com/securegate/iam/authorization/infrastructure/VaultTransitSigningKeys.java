package com.securegate.iam.authorization.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securegate.iam.authorization.application.SigningUnavailableException;
import com.securegate.iam.authorization.application.port.SigningKeys;
import com.securegate.iam.authorization.application.port.TransitEncryption;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public final class VaultTransitSigningKeys
    implements SigningKeys, TransitEncryption, AutoCloseable {
  private static final String MFA_KEY = "securegate-mfa";
  private final VaultTransitSettings settings;
  private final ObjectMapper json;
  private final HttpClient http;
  private final Semaphore requests;
  private final MeterRegistry metrics;
  private volatile Session session;
  private volatile CachedKeys cachedKeys;

  public VaultTransitSigningKeys(
      VaultTransitSettings settings, ObjectMapper json, MeterRegistry metrics) {
    this.settings = settings;
    this.json = json;
    this.http = HttpClient.newBuilder().connectTimeout(settings.timeout()).build();
    this.requests = new Semaphore(settings.maxConcurrentRequests());
    this.metrics = metrics;
  }

  @Override
  public KeySet publicKeys() {
    CachedKeys cached = cachedKeys;
    if (cached != null && Instant.now().isBefore(cached.expires())) {
      return cached.keys();
    }
    return refreshKeys();
  }

  private synchronized KeySet refreshKeys() {
    CachedKeys cached = cachedKeys;
    if (cached != null && Instant.now().isBefore(cached.expires())) {
      return cached.keys();
    }
    try {
      JsonNode data = request("transit/keys/" + settings.keyName(), null, token()).path("data");
      if (!"rsa-3072".equals(data.path("type").asText())
          || data.path("exportable").asBoolean(true)
          || data.path("allow_plaintext_backup").asBoolean(true)) {
        throw new SigningUnavailableException();
      }
      int latest = data.path("latest_version").asInt();
      var keys = new ArrayList<PublicKey>();
      var fields = data.path("keys").fields();
      while (fields.hasNext()) {
        var entry = fields.next();
        int version = Integer.parseInt(entry.getKey());
        String pem = entry.getValue().path("public_key").asText();
        byte[] der =
            Base64.getMimeDecoder()
                .decode(
                    pem.replace("-----BEGIN PUBLIC KEY-----", "")
                        .replace("-----END PUBLIC KEY-----", ""));
        RSAPublicKey rsa =
            (RSAPublicKey)
                KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        if (version < 1 || rsa.getModulus().bitLength() != 3072) {
          throw new SigningUnavailableException();
        }
        keys.add(new PublicKey(settings.keyName() + "-v" + version, version, rsa));
      }
      keys.sort(java.util.Comparator.comparingInt(PublicKey::version));
      PublicKey active =
          keys.stream().filter(key -> key.version() == latest).findFirst().orElseThrow();
      KeySet result = new KeySet(active, keys);
      cachedKeys = new CachedKeys(result, Instant.now().plus(settings.publicKeyCacheTtl()));
      return result;
    } catch (Exception exception) {
      throw new SigningUnavailableException();
    }
  }

  @Override
  public byte[] sign(PublicKey key, byte[] signingInput) {
    if (key == null
        || key.version() < 1
        || !key.id().equals(settings.keyName() + "-v" + key.version())
        || signingInput == null
        || signingInput.length == 0
        || signingInput.length > 32768) {
      throw new IllegalArgumentException("Invalid signing input.");
    }
    try {
      JsonNode response =
          request(
              "transit/sign/" + settings.keyName(),
              Map.of(
                  "input",
                  Base64.getEncoder().encodeToString(signingInput),
                  "key_version",
                  key.version(),
                  "hash_algorithm",
                  "sha2-256",
                  "signature_algorithm",
                  "pkcs1v15",
                  "prehashed",
                  false),
              token());
      String encoded = response.path("data").path("signature").asText();
      String prefix = "vault:v" + key.version() + ":";
      if (!encoded.startsWith(prefix)) {
        throw new SigningUnavailableException();
      }
      byte[] signature = Base64.getDecoder().decode(encoded.substring(prefix.length()));
      if (signature.length != 384) {
        throw new SigningUnavailableException();
      }
      return signature;
    } catch (Exception exception) {
      throw new SigningUnavailableException();
    }
  }

  @Override
  public String encrypt(byte[] plaintext, byte[] associatedData) {
    if (plaintext == null || plaintext.length == 0 || plaintext.length > 1024) {
      throw new IllegalArgumentException("Invalid encryption input.");
    }
    validateAssociatedData(associatedData);
    try {
      String ciphertext =
          request(
                  "transit/encrypt/" + MFA_KEY,
                  Map.of(
                      "plaintext", Base64.getEncoder().encodeToString(plaintext),
                      "associated_data", Base64.getEncoder().encodeToString(associatedData)),
                  token())
              .path("data")
              .path("ciphertext")
              .asText();
      if (!validCiphertext(ciphertext)) {
        throw new TransitEncryption.Unavailable();
      }
      return ciphertext;
    } catch (Exception exception) {
      throw new TransitEncryption.Unavailable();
    }
  }

  @Override
  public byte[] decrypt(String ciphertext, byte[] associatedData) {
    if (!validCiphertext(ciphertext)) {
      throw new IllegalArgumentException("Invalid ciphertext.");
    }
    validateAssociatedData(associatedData);
    try {
      JsonNode value =
          request(
                  "transit/decrypt/" + MFA_KEY,
                  Map.of(
                      "ciphertext",
                      ciphertext,
                      "associated_data",
                      Base64.getEncoder().encodeToString(associatedData)),
                  token())
              .path("data")
              .path("plaintext");
      if (!value.isTextual() || value.asText().length() > 1368) {
        throw new TransitEncryption.Unavailable();
      }
      byte[] plaintext = Base64.getDecoder().decode(value.asText());
      if (plaintext.length == 0 || plaintext.length > 1024) {
        throw new TransitEncryption.Unavailable();
      }
      return plaintext;
    } catch (Exception exception) {
      throw new TransitEncryption.Unavailable();
    }
  }

  private static boolean validCiphertext(String ciphertext) {
    return ciphertext != null
        && ciphertext.length() <= 2048
        && ciphertext.matches("vault:v[1-9][0-9]{0,8}:[A-Za-z0-9+/]+={0,2}");
  }

  private static void validateAssociatedData(byte[] associatedData) {
    if (associatedData == null || associatedData.length == 0 || associatedData.length > 256) {
      throw new IllegalArgumentException("Invalid encryption binding.");
    }
  }

  private synchronized String token() {
    Session current = session;
    if (current != null && Instant.now().isBefore(current.expires())) {
      return current.token();
    }
    try {
      String role = Files.readString(settings.roleIdFile(), StandardCharsets.UTF_8).strip();
      String secret = Files.readString(settings.secretIdFile(), StandardCharsets.UTF_8).strip();
      if (role.isBlank() || secret.isBlank() || role.length() > 1024 || secret.length() > 1024) {
        throw new SigningUnavailableException();
      }
      Instant started = Instant.now();
      JsonNode auth =
          request("auth/approle/login", Map.of("role_id", role, "secret_id", secret), null)
              .path("auth");
      String token = auth.path("client_token").asText();
      long lease = auth.path("lease_duration").asLong();
      if (token.isBlank() || lease < 2) {
        throw new SigningUnavailableException();
      }
      session = new Session(token, started.plusSeconds(lease - Math.min(30, lease / 2)));
      return token;
    } catch (Exception exception) {
      throw new SigningUnavailableException();
    }
  }

  private JsonNode request(String path, Object body, String token) {
    String operation;
    if (path.startsWith("transit/sign/")) {
      operation = "sign";
    } else if (path.startsWith("transit/encrypt/")) {
      operation = "encrypt";
    } else if (path.startsWith("transit/decrypt/")) {
      operation = "decrypt";
    } else {
      operation = token == null ? "login" : "keys";
    }
    if (!requests.tryAcquire()) {
      metrics.counter("iam.transit.rejected", "operation", operation).increment();
      throw new SigningUnavailableException();
    }
    long started = System.nanoTime();
    boolean success = false;
    try {
      HttpRequest.Builder builder =
          HttpRequest.newBuilder(settings.address().resolve("/v1/" + path))
              .timeout(settings.timeout())
              .header("Accept", "application/json");
      if (token != null) {
        builder.header("X-Vault-Token", token);
      }
      if (body == null) {
        builder.GET();
      } else {
        builder
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
      }
      HttpResponse<String> response =
          http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        if (response.statusCode() == 403 && token != null) {
          session = null;
        }
        throw new SigningUnavailableException();
      }
      JsonNode result = json.readTree(response.body());
      success = true;
      return result;
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new SigningUnavailableException();
    } catch (Exception exception) {
      throw new SigningUnavailableException();
    } finally {
      requests.release();
      metrics
          .timer(
              "iam.transit.requests",
              "operation",
              operation,
              "outcome",
              success ? "success" : "failure")
          .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
    }
  }

  @Override
  public void close() {
    http.close();
  }

  private record Session(String token, Instant expires) {
    @Override
    public String toString() {
      return "Session[redacted]";
    }
  }

  private record CachedKeys(KeySet keys, Instant expires) {}
}

package com.securegate.iam;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import com.securegate.iam.authorization.application.SigningUnavailableException;
import com.securegate.iam.authorization.application.port.SigningKeys;
import com.securegate.iam.authorization.application.port.TransitEncryption;
import com.securegate.iam.identity.application.port.MfaSecretProtector;
import com.securegate.iam.identity.domain.TotpSecret;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TransitSigningIT {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
  private static final String ROOT = UUID.randomUUID().toString();
  private static Path credentials;

  @Container
  static final GenericContainer<?> VAULT =
      new GenericContainer<>(DockerImageName.parse("hashicorp/vault:1.21.4"))
          .withEnv("VAULT_DEV_ROOT_TOKEN_ID", ROOT)
          .withEnv("SKIP_SETCAP", "true")
          .withCommand("server", "-dev", "-dev-listen-address=0.0.0.0:8200")
          .withExposedPorts(8200)
          .waitingFor(Wait.forHttp("/v1/sys/health").forStatusCode(200));

  @Container
  static final PostgreSQLContainer<?> DATABASE =
      new PostgreSQLContainer<>("postgres:16.15-alpine")
          .withDatabaseName("transit_test")
          .withUsername("transit_test")
          .withPassword(UUID.randomUUID().toString());

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) throws Exception {
    admin("sys/mounts/transit", Map.of("type", "transit"));
    admin(
        "transit/keys/securegate-jwt",
        Map.of("type", "rsa-3072", "exportable", false, "allow_plaintext_backup", false));
    admin("sys/auth/approle", Map.of("type", "approle"));
    admin(
        "transit/keys/securegate-mfa",
        Map.of("type", "aes256-gcm96", "exportable", false, "allow_plaintext_backup", false));
    // Exercise the actual application policy, not root permissions or a test approximation.
    admin(
        "sys/policies/acl/securegate-iam-core",
        Map.of("policy", Files.readString(Path.of("../infra/vault/iam-core.hcl"))));
    admin(
        "auth/approle/role/iam-core",
        Map.of("token_policies", List.of("securegate-iam-core"), "token_ttl", "1h"));
    String role =
        admin("auth/approle/role/iam-core/role-id", null).path("data").path("role_id").asText();
    String secret =
        admin("auth/approle/role/iam-core/secret-id", Map.of())
            .path("data")
            .path("secret_id")
            .asText();
    credentials = Files.createTempDirectory("securegate-transit-test-");
    Files.writeString(credentials.resolve("role"), role);
    Files.writeString(credentials.resolve("secret"), secret);
    registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
    registry.add("spring.datasource.username", DATABASE::getUsername);
    registry.add("spring.datasource.password", DATABASE::getPassword);
    registry.add("iam.transit.enabled", () -> true);
    registry.add("iam.transit.address", TransitSigningIT::vaultAddress);
    registry.add("iam.transit.role-id-file", () -> credentials.resolve("role").toString());
    registry.add("iam.transit.secret-id-file", () -> credentials.resolve("secret").toString());
    registry.add("iam.transit.public-key-cache-ttl", () -> "1s");
    registry.add("iam.transit.timeout", () -> "1s");
  }

  @Autowired JwtEncoder encoder;
  @Autowired SigningKeys keys;
  @Autowired MfaSecretProtector mfaSecrets;
  @Autowired TransitEncryption encryption;
  @LocalServerPort int port;

  @AfterAll
  static void cleanup() throws Exception {
    if (credentials != null) {
      Files.deleteIfExists(credentials.resolve("role"));
      Files.deleteIfExists(credentials.resolve("secret"));
      Files.deleteIfExists(credentials);
    }
  }

  @Test
  @Order(1)
  void signsRs256AndExposesOnlyPublicKeysWithoutOpeningOtherEndpoints() throws Exception {
    Jwt token = issue(Instant.now().plusSeconds(120));
    HttpResponse<String> response = get("/oauth2/jwks");
    assertEquals(200, response.statusCode());
    assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
    assertTrue(response.headers().firstValue("Set-Cookie").isEmpty());
    assertEquals("transit-test", response.headers().firstValue("X-Correlation-ID").orElseThrow());
    JWKSet jwks = JWKSet.parse(response.body());
    assertEquals(1, jwks.getKeys().size());
    assertTrue(jwks.getKeys().stream().noneMatch(key -> key.isPrivate()));
    assertFalse(response.body().contains("private"));
    assertFalse(response.body().contains(ROOT));
    assertFalse(response.body().contains(Files.readString(credentials.resolve("secret"))));
    verifyWithJca(token.getTokenValue(), jwks);
    Jwt verified = decoder().decode(token.getTokenValue());
    assertEquals("synthetic-client", verified.getSubject());
    assertEquals(List.of("synthetic-api"), verified.getAudience());
    assertEquals(List.of("READER"), verified.getClaimAsStringList("roles"));
    assertEquals("synthetic-session", verified.getClaimAsString("sid"));
    assertEquals(403, get("/oauth2/token").statusCode());
    assertEquals(403, get("/api/admin/users").statusCode());
    assertEquals(403, get("/actuator/env").statusCode());
    var post =
        HTTP.send(
            HttpRequest.newBuilder(appUri("/oauth2/jwks"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(403, post.statusCode());
  }

  @Test
  @Order(2)
  void rejectsExpiredWrongIssuerWrongAudienceTamperedAndUnsignedTokens() throws Exception {
    var decoder = decoder();
    Jwt valid = issue(Instant.now().plusSeconds(120));
    assertThrows(
        JwtException.class,
        () -> decoder.decode(issue(Instant.now().minusSeconds(120)).getTokenValue()));
    Jwt wrongIssuer =
        encoder.encode(
            JwtEncoderParameters.from(
                claims("https://wrong.example", "synthetic-api", Instant.now().plusSeconds(120))));
    assertThrows(JwtException.class, () -> decoder.decode(wrongIssuer.getTokenValue()));
    Jwt wrongAudience =
        encoder.encode(
            JwtEncoderParameters.from(
                claims("https://issuer.example", "wrong-api", Instant.now().plusSeconds(120))));
    assertThrows(JwtException.class, () -> decoder.decode(wrongAudience.getTokenValue()));
    String[] parts = valid.getTokenValue().split("\\.");
    String payload =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString("{\"sub\":\"attacker\"}".getBytes(StandardCharsets.UTF_8));
    assertThrows(
        JwtException.class, () -> decoder.decode(parts[0] + "." + payload + "." + parts[2]));
    String none =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
    assertThrows(JwtException.class, () -> decoder.decode(none + "." + parts[1] + "."));
    byte[] badSignature = Base64.getUrlDecoder().decode(parts[2]);
    badSignature[0] ^= 1;
    assertThrows(
        JwtException.class,
        () ->
            decoder.decode(
                parts[0]
                    + "."
                    + parts[1]
                    + "."
                    + Base64.getUrlEncoder().withoutPadding().encodeToString(badSignature)));
    assertThrows(
        JwtEncodingException.class,
        () ->
            encoder.encode(
                JwtEncoderParameters.from(
                    JwsHeader.with(SignatureAlgorithm.RS512).build(),
                    claims(
                        "https://issuer.example",
                        "synthetic-api",
                        Instant.now().plusSeconds(120)))));
    assertThrows(
        JwtEncodingException.class,
        () ->
            encoder.encode(
                JwtEncoderParameters.from(
                    JwsHeader.with(SignatureAlgorithm.RS256).keyId("untrusted-key").build(),
                    claims(
                        "https://issuer.example",
                        "synthetic-api",
                        Instant.now().plusSeconds(120)))));
  }

  @Test
  @Order(3)
  void rotationPreservesOldTokensAndPinsSignaturesDuringCacheOverlap() throws Exception {
    Jwt before = issue(Instant.now().plusSeconds(120));
    SigningKeys.PublicKey pinned = keys.publicKeys().active();
    admin("transit/keys/securegate-jwt/rotate", Map.of());
    byte[] input = "rotation-race-input".getBytes(StandardCharsets.US_ASCII);
    byte[] signature = keys.sign(pinned, input);
    Signature verifier = Signature.getInstance("SHA256withRSA");
    verifier.initVerify(pinned.key());
    verifier.update(input);
    assertTrue(verifier.verify(signature));
    // The cache may sign with v1 until its bounded TTL; it must never label v2 as v1.
    Thread.sleep(1100);
    Jwt after = issue(Instant.now().plusSeconds(120));
    assertEquals("securegate-jwt-v1", before.getHeaders().get("kid"));
    assertEquals("securegate-jwt-v2", after.getHeaders().get("kid"));
    JWKSet jwks = JWKSet.parse(get("/oauth2/jwks").body());
    assertEquals(2, jwks.getKeys().size());
    verifyWithJca(before.getTokenValue(), jwks);
    verifyWithJca(after.getTokenValue(), jwks);
    var decoder = decoder();
    assertEquals("synthetic-client", decoder.decode(before.getTokenValue()).getSubject());
    assertEquals("synthetic-client", decoder.decode(after.getTokenValue()).getSubject());
  }

  @Test
  @Order(4)
  void encryptsMfaSecretsWithAccountAndFactorBindingAndSurvivesKeyRotation() throws Exception {
    UUID user = UUID.randomUUID();
    UUID factor = UUID.randomUUID();
    TotpSecret secret = TotpSecret.generate();
    String before = mfaSecrets.protect(user, factor, secret);
    assertTrue(before.startsWith("vault:v1:"));
    assertFalse(before.contains(secret.encoded()));
    assertNotEquals(before, mfaSecrets.protect(user, factor, secret));
    assertEquals(secret.encoded(), mfaSecrets.unprotect(user, factor, before).encoded());
    assertThrows(
        MfaSecretProtector.Unavailable.class,
        () -> mfaSecrets.unprotect(UUID.randomUUID(), factor, before));
    assertThrows(
        MfaSecretProtector.Unavailable.class,
        () -> mfaSecrets.unprotect(user, UUID.randomUUID(), before));
    String[] parts = before.split(":");
    byte[] damaged = Base64.getDecoder().decode(parts[2]);
    damaged[damaged.length - 1] ^= 1;
    String tampered = parts[0] + ":" + parts[1] + ":" + Base64.getEncoder().encodeToString(damaged);
    var failure =
        assertThrows(
            MfaSecretProtector.Unavailable.class,
            () -> mfaSecrets.unprotect(user, factor, tampered));
    assertEquals("MFA secret protection is unavailable.", failure.getMessage());
    assertNull(failure.getCause());
    admin("transit/keys/securegate-mfa/rotate", Map.of());
    String after = mfaSecrets.protect(user, factor, secret);
    assertTrue(after.startsWith("vault:v2:"));
    assertEquals(secret.encoded(), mfaSecrets.unprotect(user, factor, after).encoded());
    assertEquals(secret.encoded(), mfaSecrets.unprotect(user, factor, before).encoded());
    assertEquals(2, JWKSet.parse(get("/oauth2/jwks").body()).getKeys().size());
    JsonNode metadata = admin("transit/keys/securegate-mfa", null).path("data");
    assertEquals("aes256-gcm96", metadata.path("type").asText());
    assertFalse(metadata.path("exportable").asBoolean(true));
    assertFalse(metadata.path("allow_plaintext_backup").asBoolean(true));
  }

  @Test
  @Order(4)
  void rejectsUnboundedAndUnboundEncryptionInputs() {
    byte[] binding = {1};
    byte[] input = {2};
    for (byte[] invalid : new byte[][] {null, new byte[0], new byte[1025]}) {
      assertThrows(IllegalArgumentException.class, () -> encryption.encrypt(invalid, binding));
    }
    for (byte[] invalid : new byte[][] {null, new byte[0], new byte[257]}) {
      assertThrows(IllegalArgumentException.class, () -> encryption.encrypt(input, invalid));
      assertThrows(
          IllegalArgumentException.class, () -> encryption.decrypt("vault:v1:AAAA", invalid));
    }
    for (String invalid :
        new String[] {null, "", "vault:v0:AAAA", "vault:v1:@@@", "A".repeat(2049)}) {
      assertThrows(IllegalArgumentException.class, () -> encryption.decrypt(invalid, binding));
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> mfaSecrets.protect(null, UUID.randomUUID(), TotpSecret.generate()));
    assertThrows(
        IllegalArgumentException.class,
        () -> mfaSecrets.protect(UUID.randomUUID(), null, TotpSecret.generate()));
    assertThrows(
        IllegalArgumentException.class,
        () -> mfaSecrets.protect(UUID.randomUUID(), UUID.randomUUID(), null));
    assertThrows(
        MfaSecretProtector.Unavailable.class,
        () -> mfaSecrets.unprotect(UUID.randomUUID(), UUID.randomUUID(), "bad"));
    UUID user = UUID.randomUUID();
    UUID factor = UUID.randomUUID();
    String aad = "securegate:mfa:totp:v1:" + user + ":" + factor;
    String malformed =
        encryption.encrypt(
            "not-a-seed".getBytes(StandardCharsets.US_ASCII),
            aad.getBytes(StandardCharsets.US_ASCII));
    assertThrows(
        MfaSecretProtector.Unavailable.class, () -> mfaSecrets.unprotect(user, factor, malformed));
  }

  @Test
  @Order(4)
  void enforcerPolicyCannotAccessMfaEncryptionOrDecryption() throws Exception {
    admin(
        "sys/policies/acl/securegate-policy-enforcer",
        Map.of("policy", Files.readString(Path.of("../infra/vault/policy-enforcer.hcl"))));
    String token =
        admin(
                "auth/token/create",
                Map.of(
                    "policies",
                    List.of("securegate-policy-enforcer"),
                    "no_default_policy",
                    true,
                    "ttl",
                    "1m"))
            .path("auth")
            .path("client_token")
            .asText();
    assertEquals(
        403,
        vaultRequest(
                "transit/encrypt/securegate-mfa", Map.of("plaintext", "c2VlZA=="), token, "POST")
            .statusCode());
    assertEquals(
        403,
        vaultRequest(
                "transit/decrypt/securegate-mfa",
                Map.of("ciphertext", "vault:v1:AAAA"),
                token,
                "POST")
            .statusCode());
    assertEquals(403, vaultRequest("transit/keys/securegate-mfa", null, token, "GET").statusCode());
  }

  @Test
  @Order(4)
  void appRoleCannotRotateExportOrDeleteAndCannotSubstituteARootToken() throws Exception {
    String role = Files.readString(credentials.resolve("role"));
    String secret = Files.readString(credentials.resolve("secret"));
    var login =
        vaultRequest(
            "auth/approle/login", Map.of("role_id", role, "secret_id", secret), null, "POST");
    String token = JSON.readTree(login.body()).path("auth").path("client_token").asText();
    for (String path :
        List.of(
            "transit/keys/securegate-jwt/rotate",
            "transit/export/signing-key/securegate-jwt",
            "transit/keys/securegate-jwt",
            "transit/keys/securegate-mfa/rotate",
            "transit/export/encryption-key/securegate-mfa",
            "transit/keys/securegate-mfa")) {
      String method = path.contains("rotate") ? "POST" : path.contains("export") ? "GET" : "DELETE";
      assertEquals(
          403,
          vaultRequest(path, method.equals("POST") ? Map.of() : null, token, method).statusCode());
    }
    assertFalse(token.equals(ROOT));
    admin("auth/approle/role/iam-core/secret-id/destroy", Map.of("secret_id", secret));
    // A new adapter has no session and must fail with the revoked SecretID.
    var settings =
        new com.securegate.iam.authorization.infrastructure.VaultTransitSettings(
            true,
            URI.create(vaultAddress()),
            credentials.resolve("role"),
            credentials.resolve("secret"),
            "securegate-jwt",
            Duration.ofSeconds(1),
            Duration.ofSeconds(1),
            2);
    try (var fresh =
        new com.securegate.iam.authorization.infrastructure.VaultTransitSigningKeys(
            settings, JSON, new io.micrometer.core.instrument.simple.SimpleMeterRegistry())) {
      var failure = assertThrows(SigningUnavailableException.class, fresh::publicKeys);
      assertEquals("Remote signing is unavailable.", failure.getMessage());
      assertNull(failure.getCause());
    }
    // An authenticated existing adapter keeps working until its token expires or is revoked.
    assertNotNull(issue(Instant.now().plusSeconds(120)));
  }

  @Test
  @Order(5)
  void unavailableVaultStopsSigningAndReturnsSanitized503AfterPublicCacheExpires()
      throws Exception {
    Jwt existing = issue(Instant.now().plusSeconds(120));
    JWKSet publicKeys = JWKSet.parse(get("/oauth2/jwks").body());
    UUID user = UUID.randomUUID();
    UUID factor = UUID.randomUUID();
    String ciphertext = mfaSecrets.protect(user, factor, TotpSecret.generate());
    VAULT.stop();
    var failure =
        assertThrows(
            MfaSecretProtector.Unavailable.class,
            () -> mfaSecrets.unprotect(user, factor, ciphertext));
    assertEquals("MFA secret protection is unavailable.", failure.getMessage());
    assertNull(failure.getCause());
    assertThrows(
        MfaSecretProtector.Unavailable.class,
        () -> mfaSecrets.protect(user, factor, TotpSecret.generate()));
    assertThrows(JwtEncodingException.class, () -> issue(Instant.now().plusSeconds(120)));
    verifyWithJca(existing.getTokenValue(), publicKeys);
    Thread.sleep(1100);
    var response = get("/oauth2/jwks");
    assertEquals(503, response.statusCode());
    assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
    assertEquals("5", response.headers().firstValue("Retry-After").orElseThrow());
    assertEquals("transit-test", JSON.readTree(response.body()).path("correlationId").asText());
    assertFalse(response.body().contains(ROOT));
    assertFalse(response.body().contains("127.0.0.1"));
    assertFalse(response.body().contains("ConnectException"));
  }

  private Jwt issue(Instant expiration) {
    return encoder.encode(
        JwtEncoderParameters.from(claims("https://issuer.example", "synthetic-api", expiration)));
  }

  private JwtClaimsSet claims(String issuer, String audience, Instant expiration) {
    return JwtClaimsSet.builder()
        .issuer(issuer)
        .subject("synthetic-client")
        .audience(List.of(audience))
        .issuedAt(Instant.now().minusSeconds(300))
        .expiresAt(expiration)
        .claim("roles", List.of("READER"))
        .claim("sid", "synthetic-session")
        .build();
  }

  private NimbusJwtDecoder decoder() {
    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withJwkSetUri(appUri("/oauth2/jwks").toString())
            .jwsAlgorithm(SignatureAlgorithm.RS256)
            .build();
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer("https://issuer.example"),
            jwt ->
                jwt.getAudience().contains("synthetic-api")
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"))));
    return decoder;
  }

  private void verifyWithJca(String token, JWKSet jwks) throws Exception {
    SignedJWT parsed = SignedJWT.parse(token);
    assertEquals("RS256", parsed.getHeader().getAlgorithm().getName());
    RSAKey key = (RSAKey) jwks.getKeyByKeyId(parsed.getHeader().getKeyID());
    assertNotNull(key);
    assertFalse(key.isPrivate());
    Signature verifier = Signature.getInstance("SHA256withRSA");
    verifier.initVerify(key.toRSAPublicKey());
    verifier.update(parsed.getSigningInput());
    assertTrue(verifier.verify(parsed.getSignature().decode()));
  }

  private URI appUri(String path) {
    return URI.create("http://127.0.0.1:" + port + path);
  }

  private HttpResponse<String> get(String path) throws Exception {
    return HTTP.send(
        HttpRequest.newBuilder(appUri(path))
            .timeout(Duration.ofSeconds(5))
            .header("X-Correlation-ID", "transit-test")
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static String vaultAddress() {
    return "http://" + VAULT.getHost() + ":" + VAULT.getMappedPort(8200);
  }

  private static JsonNode admin(String path, Object body) throws Exception {
    var response = vaultRequest(path, body, ROOT, body == null ? "GET" : "POST");
    assertTrue(
        response.statusCode() == 200 || response.statusCode() == 204,
        "Vault setup failed, status=" + response.statusCode());
    return response.body().isBlank() ? JSON.createObjectNode() : JSON.readTree(response.body());
  }

  private static HttpResponse<String> vaultRequest(
      String path, Object body, String token, String method) throws Exception {
    var builder =
        HttpRequest.newBuilder(URI.create(vaultAddress() + "/v1/" + path))
            .timeout(Duration.ofSeconds(5));
    if (token != null) {
      builder.header("X-Vault-Token", token);
    }
    builder
        .header("Content-Type", "application/json")
        .method(
            method,
            body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
    return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }
}

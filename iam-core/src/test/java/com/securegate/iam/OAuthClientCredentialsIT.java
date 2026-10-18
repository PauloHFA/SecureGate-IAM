package com.securegate.iam;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securegate.iam.authorization.infrastructure.AuthorizationJdbcDataSource;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
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
class OAuthClientCredentialsIT {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
  private static final String ROOT = UUID.randomUUID().toString();
  private static final String ADMIN = UUID.randomUUID().toString();
  private static final String ISSUER = "https://issuer.example.test";
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
          .withDatabaseName("oauth_test")
          .withUsername("oauth_test")
          .withPassword(UUID.randomUUID().toString());

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) throws Exception {
    vault("sys/mounts/transit", Map.of("type", "transit"));
    vault(
        "transit/keys/securegate-jwt",
        Map.of("type", "rsa-3072", "exportable", false, "allow_plaintext_backup", false));
    vault("sys/auth/approle", Map.of("type", "approle"));
    vault(
        "sys/policies/acl/securegate-iam-core",
        Map.of("policy", Files.readString(Path.of("../infra/vault/iam-core.hcl"))));
    vault(
        "auth/approle/role/iam-core",
        Map.of("token_policies", List.of("securegate-iam-core"), "token_ttl", "1h"));
    credentials = Files.createTempDirectory("securegate-oauth-test-");
    Files.writeString(
        credentials.resolve("role"),
        vault("auth/approle/role/iam-core/role-id", null).path("data").path("role_id").asText());
    Files.writeString(
        credentials.resolve("secret"),
        vault("auth/approle/role/iam-core/secret-id", Map.of())
            .path("data")
            .path("secret_id")
            .asText());
    registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
    registry.add("spring.datasource.username", DATABASE::getUsername);
    registry.add("spring.datasource.password", DATABASE::getPassword);
    registry.add("iam.transit.enabled", () -> true);
    registry.add("iam.transit.address", OAuthClientCredentialsIT::vaultAddress);
    registry.add("iam.transit.role-id-file", () -> credentials.resolve("role").toString());
    registry.add("iam.transit.secret-id-file", () -> credentials.resolve("secret").toString());
    registry.add("iam.authorization.enabled", () -> true);
    registry.add("iam.authorization.issuer", () -> ISSUER);
    registry.add("iam.bootstrap.enabled", () -> true);
    registry.add("iam.bootstrap.token", () -> ADMIN);
  }

  @Autowired JdbcTemplate jdbc;
  @Autowired DataSource dataSource;
  @Autowired RegisteredClientRepository clients;
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
  void registersConfidentialClientsAndNeverReturnsTheSecretAgain() throws Exception {
    String id = id();
    var created = register(id);
    assertEquals(201, created.statusCode());
    assertEquals("no-store", created.headers().firstValue("Cache-Control").orElseThrow());
    var body = JSON.readTree(created.body());
    String secret = body.path("clientSecret").asText();
    assertTrue(secret.matches("[a-zA-Z0-9_-]{43}"));
    assertEquals(300, body.path("client").path("accessTokenTtlSeconds").asInt());
    String stored =
        jdbc.queryForObject(
            "SELECT client_secret FROM iam_authorization.oauth2_registered_client WHERE client_id=?",
            String.class,
            id);
    assertTrue(stored.startsWith("{argon2}$argon2id$"));
    assertTrue(PasswordEncoderFactories.createDelegatingPasswordEncoder().matches(secret, stored));
    var safe = admin("/api/admin/oauth-clients/" + id, null);
    assertEquals(200, safe.statusCode());
    assertFalse(safe.body().contains(secret));
    assertFalse(safe.body().contains("clientSecret"));
    assertFalse(safe.body().contains("argon2"));
    assertEquals(409, register(id).statusCode());
    assertEquals(404, admin("/api/admin/oauth-clients/missing-client", null).statusCode());
    String details =
        jdbc.queryForObject(
            "SELECT details::text FROM iam_audit.security_events WHERE resource=?",
            String.class,
            "oauth-client:" + id);
    assertFalse(details.contains(secret));
    assertFalse(details.contains(stored));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM iam_authorization.oauth2_registered_client WHERE client_id=?",
            Integer.class,
            id));
  }

  @Test
  @Order(2)
  void anonymousAndMachineCredentialsCannotProvisionClientsOrUseOtherGrants() throws Exception {
    var anonymous =
        HTTP.send(
            HttpRequest.newBuilder(uri("/api/admin/oauth-clients"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(401, anonymous.statusCode());
    assertEquals(
        400,
        admin(
                "/api/admin/oauth-clients",
                Map.of(
                    "clientId",
                    id(),
                    "name",
                    "Invalid",
                    "audience",
                    "test-api",
                    "scopes",
                    List.of("openid")))
            .statusCode());
    var registered = JSON.readTree(register(id()).body());
    String id = registered.path("client").path("clientId").asText();
    String secret = registered.path("clientSecret").asText();
    assertError(
        token(id, "wrong-secret", "grant_type=client_credentials&scope=api:read"),
        401,
        "invalid_client");
    assertError(
        token("missing-client", secret, "grant_type=client_credentials&scope=api:read"),
        401,
        "invalid_client");
    assertError(
        token(id, secret, "grant_type=password&username=x&password=y"),
        400,
        "unsupported_grant_type");
    assertError(
        token(id, secret, "grant_type=refresh_token&refresh_token=synthetic"),
        400,
        "invalid_grant");
    assertError(
        token(id, secret, "grant_type=client_credentials&scope=api:admin"), 400, "invalid_scope");
    assertError(token(id, secret, "grant_type=client_credentials"), 400, "invalid_scope");
    assertError(
        token(
            null,
            null,
            "grant_type=client_credentials&client_id="
                + id
                + "&client_secret="
                + secret
                + "&scope=api:read"),
        401,
        "invalid_client");
    assertError(
        token(null, null, "grant_type=client_credentials&scope=api:read"), 401, "invalid_client");
    for (String path :
        List.of(
            "/.well-known/openid-configuration",
            "/.well-known/oauth-authorization-server",
            "/userinfo")) {
      assertEquals(403, get(path).statusCode());
    }
  }

  @Test
  @Order(3)
  void issuesVaultSignedTokensWithApprovedClaimsAndPersistsAuthorizationAcrossRepositoryInstances()
      throws Exception {
    var registered = JSON.readTree(register(id()).body());
    String id = registered.path("client").path("clientId").asText();
    String secret = registered.path("clientSecret").asText();
    var response = token(id, secret, "grant_type=client_credentials&scope=api:read");
    assertEquals(200, response.statusCode());
    assertTrue(response.headers().firstValue("Cache-Control").orElseThrow().contains("no-store"));
    assertTrue(response.headers().firstValue("Set-Cookie").isEmpty());
    JsonNode body = JSON.readTree(response.body());
    assertEquals("Bearer", body.path("token_type").asText());
    assertTrue(body.path("expires_in").asLong() >= 298 && body.path("expires_in").asLong() <= 300);
    assertFalse(body.has("refresh_token"));
    assertFalse(body.has("id_token"));
    String token = body.path("access_token").asText();
    var decoder = NimbusJwtDecoder.withJwkSetUri(uri("/oauth2/jwks").toString()).build();
    decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
    var jwt = decoder.decode(token);
    assertEquals(id, jwt.getSubject());
    assertEquals(id, jwt.getClaimAsString("client_id"));
    assertEquals(List.of("test-api"), jwt.getAudience());
    assertEquals(List.of("api:read"), jwt.getClaimAsStringList("scope"));
    assertFalse(jwt.hasClaim("roles"));
    assertFalse(jwt.hasClaim("sid"));
    assertNotNull(jwt.getId());
    assertEquals(300, Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt()).getSeconds());
    JdbcTemplate oauthJdbc = new JdbcTemplate(new AuthorizationJdbcDataSource(dataSource));
    var reloadedClients = new JdbcRegisteredClientRepository(oauthJdbc);
    assertEquals(id, reloadedClients.findByClientId(id).getClientId());
    var reloadedAuthorizations = new JdbcOAuth2AuthorizationService(oauthJdbc, reloadedClients);
    var authorization = reloadedAuthorizations.findByToken(token, OAuth2TokenType.ACCESS_TOKEN);
    assertNotNull(authorization);
    assertEquals(id, authorization.getPrincipalName());
    assertEquals(token, authorization.getAccessToken().getToken().getTokenValue());
    var consent = new JdbcOAuth2AuthorizationConsentService(oauthJdbc, reloadedClients);
    var registeredClient = reloadedClients.findByClientId(id);
    consent.save(
        OAuth2AuthorizationConsent.withId(registeredClient.getId(), "synthetic-user")
            .scope("api:read")
            .build());
    assertNotNull(
        new JdbcOAuth2AuthorizationConsentService(oauthJdbc, reloadedClients)
            .findById(registeredClient.getId(), "synthetic-user"));
    assertEquals("public", jdbc.queryForObject("SELECT current_schema()", String.class));
    var forbidden =
        HTTP.send(
            HttpRequest.newBuilder(uri("/api/admin/oauth-clients/" + id))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(401, forbidden.statusCode());
  }

  @Test
  @Order(4)
  void failedAuditRollsBackRegistrationOnTheSameDatabaseTransaction() throws Exception {
    String id = id();
    jdbc.execute(
        "CREATE FUNCTION iam_audit.reject_oauth_test() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.event_type='oauth.client.created' THEN RAISE EXCEPTION 'synthetic audit failure'; END IF; RETURN NEW; END; $$");
    jdbc.execute(
        "CREATE TRIGGER reject_oauth_test BEFORE INSERT ON iam_audit.security_events FOR EACH ROW EXECUTE FUNCTION iam_audit.reject_oauth_test()");
    try {
      assertEquals(500, register(id).statusCode());
      assertNull(clients.findByClientId(id));
      assertEquals("public", jdbc.queryForObject("SELECT current_schema()", String.class));
    } finally {
      jdbc.execute("DROP TRIGGER reject_oauth_test ON iam_audit.security_events");
      jdbc.execute("DROP FUNCTION iam_audit.reject_oauth_test()");
    }
  }

  @Test
  @Order(5)
  void concurrentRegistrationCreatesOneClientAndOneAuditEvent() throws Exception {
    String id = id();
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var first = executor.submit(() -> register(id).statusCode());
      var second = executor.submit(() -> register(id).statusCode());
      assertEquals(java.util.Set.of(201, 409), java.util.Set.of(first.get(), second.get()));
    }
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM iam_authorization.oauth2_registered_client WHERE client_id=?",
            Integer.class,
            id));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM iam_audit.security_events WHERE resource=?",
            Integer.class,
            "oauth-client:" + id));
  }

  @Test
  @Order(6)
  void vaultFailureReturnsOAuth503WithoutPersistingAFailedAuthorization() throws Exception {
    var registered = JSON.readTree(register(id()).body());
    String id = registered.path("client").path("clientId").asText();
    String secret = registered.path("clientSecret").asText();
    assertEquals(
        200, token(id, secret, "grant_type=client_credentials&scope=api:read").statusCode());
    Integer before =
        jdbc.queryForObject(
            "SELECT count(*) FROM iam_authorization.oauth2_authorization", Integer.class);
    VAULT.stop();
    var response = token(id, secret, "grant_type=client_credentials&scope=api:read");
    assertError(response, 503, "temporarily_unavailable");
    assertEquals("5", response.headers().firstValue("Retry-After").orElseThrow());
    assertFalse(response.body().contains("Vault"));
    assertFalse(response.body().contains(secret));
    assertFalse(response.body().contains(ROOT));
    assertEquals(
        before,
        jdbc.queryForObject(
            "SELECT count(*) FROM iam_authorization.oauth2_authorization", Integer.class));
  }

  @Test
  @Order(5)
  void introspectsAndRevokesOnlyTheOwningMachineClientsTokens() throws Exception {
    var owner = JSON.readTree(register(id()).body());
    String id = owner.path("client").path("clientId").asText();
    String secret = owner.path("clientSecret").asText();
    String access =
        JSON.readTree(token(id, secret, "grant_type=client_credentials&scope=api:read").body())
            .path("access_token")
            .asText();
    assertTrue(
        JSON.readTree(lifecycle("/oauth2/introspect", id, secret, access).body())
            .path("active")
            .asBoolean());
    var foreign = JSON.readTree(register(id()).body());
    String other = foreign.path("client").path("clientId").asText();
    String otherSecret = foreign.path("clientSecret").asText();
    var hidden = lifecycle("/oauth2/introspect", other, otherSecret, access);
    assertEquals(200, hidden.statusCode());
    assertFalse(JSON.readTree(hidden.body()).path("active").asBoolean());
    assertEquals(1, JSON.readTree(hidden.body()).size());
    assertEquals(200, lifecycle("/oauth2/revoke", other, otherSecret, access).statusCode());
    assertTrue(
        JSON.readTree(lifecycle("/oauth2/introspect", id, secret, access).body())
            .path("active")
            .asBoolean());
    assertEquals(200, lifecycle("/oauth2/revoke", id, secret, access).statusCode());
    assertEquals(200, lifecycle("/oauth2/revoke", id, secret, access).statusCode());
    assertFalse(
        JSON.readTree(lifecycle("/oauth2/introspect", id, secret, access).body())
            .path("active")
            .asBoolean());
    String authorizationId =
        jdbc.queryForObject(
            "SELECT id FROM iam_authorization.oauth2_authorization WHERE access_token_value=?",
            String.class,
            access);
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM iam_audit.security_events WHERE resource=? AND event_type='oauth.authorization.revoked'",
            Integer.class,
            authorizationId));
    String expired =
        JSON.readTree(token(id, secret, "grant_type=client_credentials&scope=api:read").body())
            .path("access_token")
            .asText();
    jdbc.update(
        "UPDATE iam_authorization.oauth2_authorization SET access_token_issued_at=current_timestamp-interval '10 minutes',access_token_expires_at=current_timestamp-interval '5 minutes' WHERE access_token_value=?",
        expired);
    assertFalse(
        JSON.readTree(lifecycle("/oauth2/introspect", id, secret, expired).body())
            .path("active")
            .asBoolean());
  }

  private HttpResponse<String> lifecycle(String path, String id, String secret, String token)
      throws Exception {
    String basic =
        Base64.getEncoder().encodeToString((id + ":" + secret).getBytes(StandardCharsets.UTF_8));
    return HTTP.send(
        HttpRequest.newBuilder(uri(path))
            .header("Authorization", "Basic " + basic)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "token=" + URLEncoder.encode(token, StandardCharsets.UTF_8)))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static String id() {
    return "test-" + UUID.randomUUID();
  }

  private URI uri(String path) {
    return URI.create("http://127.0.0.1:" + port + path);
  }

  private HttpResponse<String> get(String path) throws Exception {
    return HTTP.send(
        HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> register(String id) throws Exception {
    return admin(
        "/api/admin/oauth-clients",
        Map.of(
            "clientId",
            id,
            "name",
            "Synthetic client",
            "audience",
            "test-api",
            "scopes",
            List.of("api:read", "api:write")));
  }

  private HttpResponse<String> admin(String path, Object body) throws Exception {
    var request =
        HttpRequest.newBuilder(uri(path))
            .header("Authorization", "Bearer " + ADMIN)
            .header("Content-Type", "application/json");
    if (body == null) {
      request.GET();
    } else {
      request.POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
    }
    return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> token(String id, String secret, String form) throws Exception {
    var request =
        HttpRequest.newBuilder(uri("/oauth2/token"))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("X-Correlation-ID", "oauth-test");
    if (id != null) {
      String credentials =
          URLEncoder.encode(id, StandardCharsets.UTF_8)
              + ":"
              + URLEncoder.encode(secret, StandardCharsets.UTF_8);
      request.header(
          "Authorization",
          "Basic "
              + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
    }
    return HTTP.send(
        request.POST(HttpRequest.BodyPublishers.ofString(form)).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private void assertError(HttpResponse<String> response, int status, String error)
      throws Exception {
    assertEquals(status, response.statusCode());
    var body = JSON.readTree(response.body());
    assertEquals(error, body.path("error").asText());
    assertEquals(1, body.size());
    assertEquals("oauth-test", response.headers().firstValue("X-Correlation-ID").orElseThrow());
    assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
  }

  private static String vaultAddress() {
    return "http://" + VAULT.getHost() + ":" + VAULT.getMappedPort(8200);
  }

  private static JsonNode vault(String path, Object body) throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create(vaultAddress() + "/v1/" + path))
            .header("X-Vault-Token", ROOT)
            .header("Content-Type", "application/json");
    if (body == null) {
      request.GET();
    } else {
      request.POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
    }
    var response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    assertTrue(response.statusCode() == 200 || response.statusCode() == 204);
    return response.body().isBlank() ? JSON.createObjectNode() : JSON.readTree(response.body());
  }
}

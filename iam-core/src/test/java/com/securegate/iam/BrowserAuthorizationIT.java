package com.securegate.iam;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.util.HtmlUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BrowserAuthorizationIT {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final ObjectMapper EVENTS =
      new ObjectMapper()
          .findAndRegisterModules()
          .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  private static final HttpClient HTTP = HttpClient.newHttpClient();
  private static final String ROOT = UUID.randomUUID().toString();
  private static final String ADMIN = UUID.randomUUID().toString();
  private static final String PASSWORD = "Synthetic-password-123!";
  private static final String CALLBACK = "http://127.0.0.1:18090/callback";
  private static final String ISSUER = "https://issuer.example.test";
  private static Path credentials;
  private static final org.testcontainers.containers.Network PAM_NETWORK =
      org.testcontainers.containers.Network.newNetwork();

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7.4.11-alpine"))
          .withCommand("redis-server", "--requirepass", ROOT)
          .withExposedPorts(6379);

  @Container
  static final GenericContainer<?> VAULT =
      new GenericContainer<>(DockerImageName.parse("hashicorp/vault:1.21.4"))
          .withNetwork(PAM_NETWORK)
          .withEnv("VAULT_DEV_ROOT_TOKEN_ID", ROOT)
          .withEnv("SKIP_SETCAP", "true")
          .withCommand("server", "-dev", "-dev-listen-address=0.0.0.0:8200")
          .withExposedPorts(8200)
          .waitingFor(Wait.forHttp("/v1/sys/health").forStatusCode(200));

  @Container
  static final PostgreSQLContainer<?> DATABASE =
      new PostgreSQLContainer<>("postgres:16.15-alpine")
          .withNetwork(PAM_NETWORK)
          .withNetworkAliases("postgres")
          .withDatabaseName("browser_test")
          .withUsername("browser_test")
          .withPassword(UUID.randomUUID().toString());

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) throws Exception {
    vault("sys/mounts/transit", Map.of("type", "transit"));
    vault(
        "transit/keys/securegate-jwt",
        Map.of("type", "rsa-3072", "exportable", false, "allow_plaintext_backup", false));
    vault("sys/auth/approle", Map.of("type", "approle"));
    vault(
        "transit/keys/securegate-mfa",
        Map.of("type", "aes256-gcm96", "exportable", false, "allow_plaintext_backup", false));
    vault(
        "sys/policies/acl/securegate-iam-core",
        Map.of("policy", Files.readString(Path.of("../infra/vault/iam-core.hcl"))));
    vault(
        "auth/approle/role/iam-core",
        Map.of("token_policies", List.of("securegate-iam-core"), "token_ttl", "1h"));
    credentials = Files.createTempDirectory("securegate-browser-test-");
    Files.writeString(
        credentials.resolve("role"),
        vault("auth/approle/role/iam-core/role-id", null).path("data").path("role_id").asText());
    Files.writeString(
        credentials.resolve("secret"),
        vault("auth/approle/role/iam-core/secret-id", Map.of())
            .path("data")
            .path("secret_id")
            .asText());
    configurePam();
    registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
    registry.add("spring.datasource.username", DATABASE::getUsername);
    registry.add("spring.datasource.password", DATABASE::getPassword);
    registry.add("iam.resources.enforcement.enabled", () -> true);
    registry.add("iam.resources.enforcement.host", REDIS::getHost);
    registry.add("iam.resources.enforcement.port", () -> REDIS.getMappedPort(6379));
    registry.add("iam.resources.enforcement.password", () -> ROOT);
    registry.add("iam.transit.enabled", () -> true);
    registry.add("iam.transit.address", BrowserAuthorizationIT::vaultAddress);
    registry.add("iam.transit.role-id-file", () -> credentials.resolve("role").toString());
    registry.add("iam.transit.secret-id-file", () -> credentials.resolve("secret").toString());
    registry.add("iam.authorization.enabled", () -> true);
    registry.add("iam.pam.enabled", () -> true);
    registry.add("iam.pam.credentials.enabled", () -> true);
    registry.add("iam.pam.credentials.address", BrowserAuthorizationIT::vaultAddress);
    registry.add(
        "iam.pam.credentials.role-id-file", () -> credentials.resolve("pam-role").toString());
    registry.add(
        "iam.pam.credentials.secret-id-file", () -> credentials.resolve("pam-secret").toString());
    registry.add("iam.authorization.issuer", () -> ISSUER);
    registry.add("iam.resources.audience", () -> "browser-api");
    registry.add("iam.bootstrap.enabled", () -> true);
    registry.add("iam.bootstrap.token", () -> ADMIN);
  }

  @AfterAll
  static void cleanup() throws Exception {
    if (credentials != null) {
      Files.deleteIfExists(credentials.resolve("role"));
      Files.deleteIfExists(credentials.resolve("secret"));
      Files.deleteIfExists(credentials.resolve("pam-role"));
      Files.deleteIfExists(credentials.resolve("pam-secret"));
      Files.deleteIfExists(credentials);
    }
  }

  private static void configurePam() throws Exception {
    org.flywaydb.core.Flyway.configure()
        .dataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword())
        .load()
        .migrate();
    String password =
        UUID.randomUUID().toString().replace("-", "")
            + UUID.randomUUID().toString().replace("-", "");
    try (var connection =
        java.sql.DriverManager.getConnection(
            DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword())) {
      try (var statement =
          connection.prepareStatement(
              "SELECT set_config('securegate.pam_issuer_password',?,false)")) {
        statement.setString(1, password);
        statement.execute();
      }
      try (var statement = connection.createStatement()) {
        statement.execute(Files.readString(Path.of("../infra/postgres/pam-diagnostics.sql")));
      }
    }
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
            "postgresql://{{username}}:{{password}}@postgres:5432/browser_test?sslmode=disable",
            "username",
            "securegate_vault_pam",
            "password",
            password,
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
        "sys/policies/acl/securegate-iam-pam",
        Map.of("policy", Files.readString(Path.of("../infra/vault/iam-pam.hcl"))));
    vault(
        "auth/approle/role/iam-pam",
        Map.of("token_policies", "securegate-iam-pam", "token_ttl", "5m", "token_max_ttl", "5m"));
    Files.writeString(
        credentials.resolve("pam-role"),
        vault("auth/approle/role/iam-pam/role-id", null).path("data").path("role_id").asText());
    Files.writeString(
        credentials.resolve("pam-secret"),
        vault("auth/approle/role/iam-pam/secret-id", Map.of())
            .path("data")
            .path("secret_id")
            .asText());
  }

  @Autowired JdbcTemplate jdbc;
  @Autowired com.securegate.iam.authorization.application.PamCredentialService pamCredentials;
  @Autowired com.securegate.iam.authorization.application.PrivilegedRequestService pamRequests;
  @org.springframework.test.context.bean.override.mockito.MockitoSpyBean java.time.Clock riskClock;
  @Autowired com.securegate.iam.identity.application.port.LoginRiskHistory loginHistory;
  @Autowired com.securegate.iam.identity.application.MfaService mfa;
  @Autowired com.securegate.iam.authorization.application.port.ResourceTokens resourceTokens;

  @Autowired
  @org.springframework.beans.factory.annotation.Qualifier("enforcementRedisTemplate")
  org.springframework.data.redis.core.StringRedisTemplate redisEnforcement;

  private List<com.securegate.events.SecurityEvent> facts(String type, String key, String value) {
    return jdbc.query(
        "SELECT payload::text FROM iam_audit.outbox_events WHERE event_type=? AND payload->'data'->>?=? ORDER BY occurred_at,event_id",
        (rs, row) -> {
          try {
            return EVENTS.readValue(rs.getString(1), com.securegate.events.SecurityEvent.class);
          } catch (Exception invalid) {
            throw new IllegalStateException("Invalid stored event.");
          }
        },
        type,
        key,
        value);
  }

  private HttpResponse<String> loginAttempt(HttpClient browser, String email, String password)
      throws Exception {
    var login = get(browser, "/login");
    return post(
        browser,
        "/login",
        Map.of("username", email, "password", password, "_csrf", hidden(login.body(), "_csrf")));
  }

  @Test
  void failedLoginFactsShareTheAttemptWithHistoryAndNeverExposeTheUsername() throws Exception {
    Flow flow = flow();
    HttpClient browser = browser();
    assertEquals(302, loginAttempt(browser, flow.email(), "wrong-password").statusCode());
    var failed = facts("auth.login.failed", "userId", flow.userId().toString());
    assertEquals(1, failed.size());
    var fact = failed.getFirst();
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM iam_identity.login_failures WHERE id=? AND user_id=?",
            Integer.class,
            UUID.fromString(fact.data().get("attemptId")),
            flow.userId()));
    assertFalse(EVENTS.writeValueAsString(fact).contains(flow.email()));
    org.mockito.Mockito.doThrow(new IllegalStateException("synthetic failed-login event failure"))
        .when(mfaAudit)
        .record(
            org.mockito.ArgumentMatchers.argThat(
                event -> event.type().value().equals("auth.login.failed")));
    assertEquals(302, loginAttempt(browser, flow.email(), "wrong-password").statusCode());
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM iam_identity.login_failures WHERE user_id=?",
            Integer.class,
            flow.userId()));
    assertEquals(1, facts("auth.login.failed", "userId", flow.userId().toString()).size());
    org.mockito.Mockito.reset(mfaAudit);
    String correlation = "unknown-" + UUID.randomUUID();
    var login = get(browser, "/login");
    var response =
        browser.send(
            HttpRequest.newBuilder(uri("/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("X-Correlation-ID", correlation)
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        form(
                            Map.of(
                                "username",
                                "unknown-" + UUID.randomUUID() + "@example.test",
                                "password",
                                "wrong-password",
                                "_csrf",
                                hidden(login.body(), "_csrf")))))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(302, response.statusCode());
    var unknown =
        JSON.readTree(
            jdbc.queryForObject(
                "SELECT payload::text FROM iam_audit.outbox_events WHERE payload->>'correlationId'=?",
                String.class,
                correlation));
    assertFalse(unknown.path("data").has("userId"));
    assertEquals(3, unknown.path("data").size());
    assertEquals("127.0.0.1", unknown.path("data").path("remoteAddress").asText());
  }

  @Test
  void suspiciousLoginIsAssessedOncePerSidAndLogoutProducesATerminalFact() throws Exception {
    Flow flow = flow();
    HttpClient browser = mfaLogin(flow);
    var suspicious = facts("auth.login.suspicious", "userId", flow.userId().toString());
    assertEquals(1, suspicious.size());
    var fact = suspicious.getFirst();
    assertTrue(Integer.parseInt(fact.data().get("riskScore")) >= 70);
    assertTrue(fact.data().get("reasons").contains("DEVICE_UNTRUSTED"));
    get(browser, "/mfa");
    get(browser, "/mfa");
    assertEquals(1, facts("auth.login.suspicious", "userId", flow.userId().toString()).size());
    var page = get(browser, "/mfa");
    assertEquals(
        302, post(browser, "/logout", Map.of("_csrf", hidden(page.body(), "_csrf"))).statusCode());
    var closed = facts("session.terminated", "sid", fact.data().get("sid"));
    assertEquals(1, closed.size());
    assertEquals(flow.userId().toString(), closed.getFirst().data().get("userId"));
    assertNotNull(
        jdbc.queryForObject(
            "SELECT closed_at FROM iam_policy.browser_contexts WHERE id=?",
            java.sql.Timestamp.class,
            UUID.fromString(fact.data().get("sid"))));
    assertEquals(302, get(browser, "/mfa").statusCode());
  }

  @Test
  void suspiciousEventFailureCannotLeaveAnAuthenticatedBrowserOrCommittedContext()
      throws Exception {
    Flow flow = flow();
    org.mockito.Mockito.doThrow(new IllegalStateException("synthetic suspicious event failure"))
        .when(mfaAudit)
        .record(
            org.mockito.ArgumentMatchers.argThat(
                event -> event.type().value().equals("auth.login.suspicious")));
    HttpClient browser = browser();
    var response = loginAttempt(browser, flow.email(), PASSWORD);
    assertEquals(503, response.statusCode(), response.body());
    assertEquals(302, get(browser, "/mfa").statusCode());
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM iam_policy.browser_contexts WHERE user_id=?",
            Integer.class,
            flow.userId()));
    assertTrue(facts("auth.login.suspicious", "userId", flow.userId().toString()).isEmpty());
  }

  @Test
  void lowRiskReauthenticationDoesNotEmitSuspicionAndTerminatesTheOldSid() throws Exception {
    Flow flow = flow();
    HttpClient browser = mfaLogin(flow);
    String oldSid =
        facts("auth.login.suspicious", "userId", flow.userId().toString())
            .getFirst()
            .data()
            .get("sid");
    var now = java.time.Instant.now().plusSeconds(600);
    org.mockito.Mockito.doReturn(now).when(riskClock).instant();
    UUID network = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO iam_policy.trusted_networks(id,network,enabled) VALUES (?,'127.0.0.0/24',true)",
        network);
    jdbc.update(
        "INSERT INTO iam_policy.resource_profiles(resource,sensitivity) VALUES ('auth:login','PUBLIC')");
    jdbc.update(
        "UPDATE iam_policy.browser_devices SET trusted_until=? WHERE user_id=?",
        java.sql.Timestamp.from(now.plusSeconds(3600)),
        flow.userId());
    try {
      assertEquals(302, loginAttempt(browser, flow.email(), PASSWORD).statusCode());
      assertEquals(1, facts("auth.login.suspicious", "userId", flow.userId().toString()).size());
      assertEquals(1, facts("session.terminated", "sid", oldSid).size());
    } finally {
      jdbc.update("DELETE FROM iam_policy.trusted_networks WHERE id=?", network);
      jdbc.update("DELETE FROM iam_policy.resource_profiles WHERE resource='auth:login'");
    }
  }

  @Test
  void sessionEventFailureRollsBackContextAndMfaClosureAndAllowsRetry() throws Exception {
    Flow flow = flow();
    HttpClient browser = mfaLogin(flow);
    String sid =
        facts("auth.login.suspicious", "userId", flow.userId().toString())
            .getFirst()
            .data()
            .get("sid");
    String seed = mfaSeed(mfaPost(browser, "/mfa/enroll", Map.of("password", PASSWORD)));
    mfaRecovery(mfaPost(browser, "/mfa/confirm", Map.of("code", mfaCode(seed))));
    assertTrue(mfaProofs.current(flow.userId(), 0, UUID.fromString(sid)).isPresent());
    org.mockito.Mockito.doThrow(new IllegalStateException("synthetic session event failure"))
        .when(mfaAudit)
        .record(
            org.mockito.ArgumentMatchers.argThat(
                event -> event.type().value().equals("session.terminated")));
    var page = get(browser, "/mfa");
    assertEquals(
        500, post(browser, "/logout", Map.of("_csrf", hidden(page.body(), "_csrf"))).statusCode());
    assertTrue(mfaProofs.current(flow.userId(), 0, UUID.fromString(sid)).isPresent());
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM iam_policy.browser_contexts WHERE id=? AND closed_at IS NOT NULL",
            Integer.class,
            UUID.fromString(sid)));
    assertTrue(facts("session.terminated", "sid", sid).isEmpty());
    org.mockito.Mockito.reset(mfaAudit);
    page = get(browser, "/mfa");
    assertEquals(200, page.statusCode());
    assertEquals(
        302, post(browser, "/logout", Map.of("_csrf", hidden(page.body(), "_csrf"))).statusCode());
    assertTrue(mfaProofs.current(flow.userId(), 0, UUID.fromString(sid)).isEmpty());
    assertEquals(1, facts("session.terminated", "sid", sid).size());
  }

  @Test
  void revocationFactsTargetFamilyAcrossRefreshAndSingleAccessTokenWithoutRefresh()
      throws Exception {
    Flow family = flow(true);
    Pair initial = initialTokens(family);
    String authorizationId = authorizationId(initial.access());
    var renewed = pair(refresh(family, initial.refresh(), null));
    assertEquals(
        authorizationId,
        com.nimbusds.jwt.SignedJWT.parse(initial.access())
            .getJWTClaimsSet()
            .getStringClaim("authorization_id"));
    assertEquals(
        authorizationId,
        com.nimbusds.jwt.SignedJWT.parse(renewed.access())
            .getJWTClaimsSet()
            .getStringClaim("authorization_id"));
    assertEquals(
        200, oauthPost("/oauth2/revoke", family, Map.of("token", initial.refresh())).statusCode());
    var revoked = facts("token.revoked", "authorizationId", authorizationId);
    assertEquals(1, revoked.size());
    assertEquals("AUTHORIZATION", revoked.getFirst().data().get("target"));
    assertEquals(family.userId().toString(), revoked.getFirst().data().get("userId"));
    assertTrue(
        java.time.Instant.parse(revoked.getFirst().data().get("expiresAt"))
            .isAfter(java.time.Instant.now()));
    assertEquals(
        200, oauthPost("/oauth2/revoke", family, Map.of("token", renewed.access())).statusCode());
    assertEquals(1, facts("token.revoked", "authorizationId", authorizationId).size());
    Flow single = flow(false);
    var browser = browser();
    String code = approve(browser, single, pending(browser, single));
    String access =
        JSON.readTree(exchange(single, code, single.verifier(), CALLBACK).body())
            .path("access_token")
            .asText();
    String singleId = authorizationId(access);
    assertEquals(200, oauthPost("/oauth2/revoke", single, Map.of("token", access)).statusCode());
    var singleFact = facts("token.revoked", "authorizationId", singleId).getFirst();
    assertEquals("ACCESS_TOKEN", singleFact.data().get("target"));
    assertEquals(
        com.nimbusds.jwt.SignedJWT.parse(access).getJWTClaimsSet().getJWTID(),
        singleFact.data().get("jti"));
    assertFalse(EVENTS.writeValueAsString(singleFact).contains(access));
  }

  @Test
  void refreshReuseFactAndFamilyInvalidationRollbackTogetherWhenPersistenceFails()
      throws Exception {
    Flow flow = flow(true);
    Pair first = initialTokens(flow);
    Pair second = pair(refresh(flow, first.refresh(), null));
    String id = authorizationId(second.access());
    org.mockito.Mockito.doThrow(new IllegalStateException("synthetic revocation event failure"))
        .when(mfaAudit)
        .record(
            org.mockito.ArgumentMatchers.argThat(
                event -> event.type().value().equals("token.revoked")));
    assertEquals(500, refresh(flow, first.refresh(), null).statusCode());
    assertActive(flow, second.access(), true);
    assertTrue(facts("token.revoked", "authorizationId", id).isEmpty());
    org.mockito.Mockito.reset(mfaAudit);
    assertError(refresh(flow, first.refresh(), null), "invalid_grant");
    var fact = facts("token.revoked", "authorizationId", id).getFirst();
    assertEquals("REFRESH_REUSE", fact.data().get("reason"));
    assertEquals("AUTHORIZATION", fact.data().get("target"));
    assertActive(flow, second.access(), false);
    assertError(refresh(flow, first.refresh(), null), "invalid_grant");
    assertEquals(1, facts("token.revoked", "authorizationId", id).size());
  }

  @org.junit.jupiter.api.AfterEach
  void removeResourcePolicies() {
    jdbc.update("DELETE FROM iam_policy.policies WHERE document->>'resource'='account:self'");
    jdbc.update(
        "DELETE FROM iam_policy.policies WHERE document->>'resource'='database:diagnostics'");
    redisEnforcement.execute(
        (org.springframework.data.redis.core.RedisCallback<Void>)
            connection -> {
              connection.serverCommands().flushDb();
              return null;
            });
  }

  private String enforcementKey(String group, String kind, String subject) {
    return com.securegate.iam.authorization.infrastructure.RedisResourceEnforcement.key(
        group, kind, subject);
  }

  @Test
  void loginIpBlockIsUniformAfterCsrfAndDoesNotRecordFakeCredentialFailures() throws Exception {
    Flow flow = flow(true);
    var browser = browser();
    int before =
        jdbc.queryForObject(
            "SELECT count(*) FROM iam_audit.outbox_events WHERE event_type='auth.login.failed'",
            Integer.class);
    String ip = enforcementKey("block-policy", "blocked:ip", "127.0.0.1");
    markEnforcement(ip, Long.toString(System.currentTimeMillis() + 120000));
    for (String email : List.of(flow.email(), "unknown-" + UUID.randomUUID() + "@example.test")) {
      var response = loginAttempt(browser, email, PASSWORD);
      assertEquals(429, response.statusCode(), response.body());
      assertEquals("{\"error\":\"rate_limited\"}", response.body());
    }
    var noCsrf = post(browser, "/login", Map.of("username", flow.email(), "password", PASSWORD));
    assertEquals(403, noCsrf.statusCode());
    assertEquals(
        before,
        jdbc.queryForObject(
            "SELECT count(*) FROM iam_audit.outbox_events WHERE event_type='auth.login.failed'",
            Integer.class));
    redisEnforcement.delete(ip);
    assertEquals(302, loginAttempt(browser, flow.email(), PASSWORD).statusCode());
    assertEquals(200, get(browser, "/mfa").statusCode());
  }

  @Test
  void accountAndIdentityLoginDenialsStayGenericAndMalformedStateIs503WithoutFailureFact()
      throws Exception {
    Flow flow = flow(true);
    var browser = browser();
    long version =
        jdbc.queryForObject(
            "SELECT version FROM iam_identity.users WHERE id=?", Long.class, flow.userId());
    String account =
        enforcementKey("block-policy", "blocked:account", flow.userId() + ":" + version);
    markEnforcement(account, Long.toString(System.currentTimeMillis() + 120000));
    assertEquals(
        uri("/login?error").toString(), location(loginAttempt(browser, flow.email(), PASSWORD)));
    assertEquals(
        uri("/login?error").toString(),
        location(loginAttempt(browser, flow.email(), "wrong-synthetic-password")));
    assertEquals(302, get(browser, "/mfa").statusCode());
    redisEnforcement.delete(account);
    String identity = enforcementKey("block-policy", "identity", flow.userId().toString());
    markEnforcement(identity, String.format(java.util.Locale.ROOT, "%019d|LOCKED", version));
    assertEquals(
        uri("/login?error").toString(), location(loginAttempt(browser, flow.email(), PASSWORD)));
    int before =
        jdbc.queryForObject(
            "SELECT count(*) FROM iam_audit.outbox_events WHERE event_type='auth.login.failed'",
            Integer.class);
    markEnforcement(identity, "synthetic-invalid-state");
    var unavailable = loginAttempt(browser, flow.email(), PASSWORD);
    assertEquals(503, unavailable.statusCode());
    assertFalse(unavailable.body().contains("synthetic"));
    assertEquals(
        before,
        jdbc.queryForObject(
            "SELECT count(*) FROM iam_audit.outbox_events WHERE event_type='auth.login.failed'",
            Integer.class));
    redisEnforcement.delete(identity);
    assertEquals(302, loginAttempt(browser, flow.email(), PASSWORD).statusCode());
    assertEquals(200, get(browser, "/mfa").statusCode());
  }

  @Test
  void distributedRefreshDenialsPreserveOriginalRefreshUntilCauseIsRemoved() throws Exception {
    Flow flow = flow(true);
    Pair tokens = initialTokens(flow);
    var claims = com.nimbusds.jwt.SignedJWT.parse(tokens.access()).getJWTClaimsSet();
    long version =
        jdbc.queryForObject(
            "SELECT version FROM iam_identity.users WHERE id=?", Long.class, flow.userId());
    String deadline = Long.toString(System.currentTimeMillis() + 120000);
    var marks = new java.util.LinkedHashMap<String, String>();
    marks.put(enforcementKey("revocation", "sid", claims.getStringClaim("sid")), "0");
    marks.put(
        enforcementKey("revocation", "authorization", authorizationId(tokens.access())), deadline);
    marks.put(
        enforcementKey("block-policy", "blocked:account", flow.userId() + ":" + version), deadline);
    marks.put(enforcementKey("block-policy", "blocked:ip", "127.0.0.1"), deadline);
    marks.put(
        enforcementKey("block-policy", "identity", flow.userId().toString()),
        String.format(java.util.Locale.ROOT, "%019d|LOCKED", version));
    for (var mark : marks.entrySet()) {
      markEnforcement(mark.getKey(), mark.getValue());
      assertError(refresh(flow, tokens.refresh(), null), "invalid_grant");
      redisEnforcement.delete(mark.getKey());
      tokens = pair(refresh(flow, tokens.refresh(), null));
    }
  }

  @Test
  void distributedPolicyWatermarkIsCheckedAgainAtRefresh() throws Exception {
    Flow flow = flow(true);
    Pair tokens = initialTokens(flow);
    var row =
        jdbc.queryForMap(
            "SELECT id,version FROM iam_policy.policies WHERE document->>'resource'=?",
            "oauth:" + flow.clientId());
    String key = enforcementKey("block-policy", "policy", row.get("id").toString());
    markEnforcement(
        key,
        String.format(
            java.util.Locale.ROOT,
            "%019d|true|%s",
            ((Number) row.get("version")).longValue() + 1,
            "a".repeat(64)));
    assertError(refresh(flow, tokens.refresh(), null), "invalid_grant");
    redisEnforcement.delete(key);
    assertEquals(200, refresh(flow, tokens.refresh(), null).statusCode());
  }

  @Test
  void redisRiskRequiresRealMfaForRefreshAndBoundsTokenToOriginalSessionProof() throws Exception {
    Flow flow = flow(true);
    HttpClient browser = mfaLogin(flow);
    String code = approve(browser, flow, get(browser, location(get(browser, authorize(flow)))));
    Pair tokens = pair(exchange(flow, code, flow.verifier(), CALLBACK));
    String sid =
        com.nimbusds.jwt.SignedJWT.parse(tokens.access()).getJWTClaimsSet().getStringClaim("sid");
    markEnforcement(
        enforcementKey("block-policy", "risk", sid),
        Long.toString(System.currentTimeMillis() + 120000));
    assertError(refresh(flow, tokens.refresh(), null), "invalid_grant");
    String seed = mfaSeed(mfaPost(browser, "/mfa/enroll", Map.of("password", PASSWORD)));
    var activation = mfaPost(browser, "/mfa/confirm", Map.of("code", mfaCode(seed)));
    var codes = mfaRecovery(activation);
    assertEquals(
        200,
        mfaPost(browser, "/mfa/verify", Map.of("method", "RECOVERY", "code", codes.getFirst()))
            .statusCode());
    var renewed = pair(refresh(flow, tokens.refresh(), null));
    var proof = mfaProofs.current(flow.userId(), 0, UUID.fromString(sid)).orElseThrow();
    var expires =
        com.nimbusds.jwt.SignedJWT.parse(renewed.access())
            .getJWTClaimsSet()
            .getExpirationTime()
            .toInstant();
    assertFalse(expires.isAfter(proof.expiresAt()));
  }

  @Test
  void redisOutageOnLoginAndRefreshFailsClosedAndRefreshRecoversWithoutRotationLoss()
      throws Exception {
    Flow flow = flow(true);
    Pair tokens = initialTokens(flow);
    resourcePolicy("ALLOW", List.of(), null);
    var browser = browser();
    var page = get(browser, "/login");
    int before =
        jdbc.queryForObject(
            "SELECT count(*) FROM iam_audit.outbox_events WHERE event_type='auth.login.failed'",
            Integer.class);
    assertEquals(
        0,
        REDIS
            .execInContainer("redis-cli", "-a", ROOT, "CLIENT", "PAUSE", "1500", "ALL")
            .getExitCode());
    var denied =
        post(
            browser,
            "/login",
            Map.of(
                "username",
                flow.email(),
                "password",
                PASSWORD,
                "_csrf",
                hidden(page.body(), "_csrf")));
    assertEquals(503, denied.statusCode());
    assertEquals("5", denied.headers().firstValue("Retry-After").orElseThrow());
    assertEquals(
        before,
        jdbc.queryForObject(
            "SELECT count(*) FROM iam_audit.outbox_events WHERE event_type='auth.login.failed'",
            Integer.class));
    long timeout = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
    HttpResponse<String> response;
    do {
      Thread.sleep(100);
      response = resource(tokens.access());
    } while (response.statusCode() == 503 && System.nanoTime() < timeout);
    assertEquals(200, response.statusCode());
    assertEquals(
        0,
        REDIS
            .execInContainer("redis-cli", "-a", ROOT, "CLIENT", "PAUSE", "1500", "ALL")
            .getExitCode());
    var unavailable = refresh(flow, tokens.refresh(), null);
    assertEquals(503, unavailable.statusCode());
    timeout = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
    do {
      Thread.sleep(100);
      response = refresh(flow, tokens.refresh(), null);
    } while (response.statusCode() == 503 && System.nanoTime() < timeout);
    assertEquals(200, response.statusCode(), response.body());
  }

  @Test
  void distributedCodeRedemptionDenialRollsBackAndDoesNotConsumeCode() throws Exception {
    Flow flow = flow(false);
    var browser = browser();
    String code = approve(browser, flow, pending(browser, flow));
    var authorization =
        authorizations.findByToken(
            code,
            new org.springframework.security.oauth2.server.authorization.OAuth2TokenType("code"));
    String sid =
        authorization.getAttribute(
            com.securegate.iam.authorization.application.port.ConsentRequests.SESSION_ID);
    String key = enforcementKey("revocation", "sid", sid);
    markEnforcement(key, "0");
    assertError(exchange(flow, code, flow.verifier(), CALLBACK), "invalid_grant");
    redisEnforcement.delete(key);
    assertEquals(200, exchange(flow, code, flow.verifier(), CALLBACK).statusCode());
  }

  private void markEnforcement(String key, String value) {
    redisEnforcement.opsForValue().set(key, value, Duration.ofSeconds(120));
  }

  @Test
  void distributedRevocationsDenyValidLocalTokensByJtiSidAndStoredAuthorizationId()
      throws Exception {
    Flow flow = flow(true);
    Pair tokens = initialTokens(flow);
    resourcePolicy("ALLOW", List.of(), null);
    var claims = com.nimbusds.jwt.SignedJWT.parse(tokens.access()).getJWTClaimsSet();
    String deadline = Long.toString(System.currentTimeMillis() + 120000);
    var marks =
        Map.of(
            "jti",
            claims.getJWTID(),
            "sid",
            claims.getStringClaim("sid"),
            "authorization",
            authorizationId(tokens.access()));
    for (var mark : marks.entrySet()) {
      assertEquals(200, resource(tokens.access()).statusCode());
      String key = enforcementKey("revocation", mark.getKey(), mark.getValue());
      markEnforcement(key, mark.getKey().equals("sid") ? "0" : deadline);
      var response = resource(tokens.access());
      assertEquals(401, response.statusCode(), response.body());
      assertEquals("{\"error\":\"invalid_token\"}", response.body());
      redisEnforcement.delete(key);
    }
    assertEquals(200, resource(tokens.access()).statusCode());
  }

  @Test
  void distributedIdentityWatermarkCannotBeBypassedAndOlderProjectionDoesNotOverrideFreshDatabase()
      throws Exception {
    Flow flow = flow(true);
    jdbc.update("UPDATE iam_identity.users SET version=version+1 WHERE id=?", flow.userId());
    Pair tokens = initialTokens(flow);
    resourcePolicy("ALLOW", List.of(), null);
    long version =
        jdbc.queryForObject(
            "SELECT version FROM iam_identity.users WHERE id=?", Long.class, flow.userId());
    String key = enforcementKey("block-policy", "identity", flow.userId().toString());
    assertEquals(200, resource(tokens.access()).statusCode());
    markEnforcement(key, String.format(java.util.Locale.ROOT, "%019d|LOCKED", version));
    assertEquals(401, resource(tokens.access()).statusCode());
    markEnforcement(key, String.format(java.util.Locale.ROOT, "%019d|ACTIVE", version + 1));
    assertEquals(401, resource(tokens.access()).statusCode());
    markEnforcement(key, String.format(java.util.Locale.ROOT, "%019d|ACTIVE", version));
    assertEquals(200, resource(tokens.access()).statusCode());
    if (version > 0) {
      markEnforcement(key, String.format(java.util.Locale.ROOT, "%019d|LOCKED", version - 1));
      assertEquals(200, resource(tokens.access()).statusCode());
    }
  }

  @Test
  void resourceRateLimitsAccountAndActualPeerIpWithoutTrustingForwardedHeaders() throws Exception {
    Flow flow = flow(true);
    Pair tokens = initialTokens(flow);
    resourcePolicy("ALLOW", List.of(), null);
    long version =
        jdbc.queryForObject(
            "SELECT version FROM iam_identity.users WHERE id=?", Long.class, flow.userId());
    String account =
        enforcementKey("block-policy", "blocked:account", flow.userId() + ":" + version);
    markEnforcement(account, Long.toString(System.currentTimeMillis() + 120000));
    var denied = resource(tokens.access());
    assertEquals(429, denied.statusCode());
    assertEquals("{\"error\":\"rate_limited\"}", denied.body());
    assertEquals("5", denied.headers().firstValue("Retry-After").orElseThrow());
    assertTrue(denied.headers().allValues("WWW-Authenticate").isEmpty());
    redisEnforcement.delete(account);
    String ip = enforcementKey("block-policy", "blocked:ip", "127.0.0.1");
    markEnforcement(ip, Long.toString(System.currentTimeMillis() + 120000));
    var response =
        HTTP.send(
            HttpRequest.newBuilder(uri("/api/resources/account"))
                .header("Authorization", "Bearer " + tokens.access())
                .header("X-Forwarded-For", "203.0.113.99")
                .header("Forwarded", "for=203.0.113.99")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(429, response.statusCode(), response.body());
    redisEnforcement.delete(ip);
    assertEquals(200, resource(tokens.access()).statusCode());
    markEnforcement(account, "123");
    redisEnforcement.expire(account, Duration.ofSeconds(1));
    assertEquals(429, resource(tokens.access()).statusCode());
    long timeout = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
    while (Boolean.TRUE.equals(redisEnforcement.hasKey(account)) && System.nanoTime() < timeout) {
      Thread.sleep(25);
    }
    assertEquals(200, resource(tokens.access()).statusCode());
  }

  @Test
  void policyWatermarkRejectsNewerOrContradictoryProjectionButNeverGrantsAccessByItself()
      throws Exception {
    Flow flow = flow(true);
    Pair tokens = initialTokens(flow);
    resourcePolicy("ALLOW", List.of(), null);
    var row =
        jdbc.queryForMap(
            "SELECT id,version FROM iam_policy.policies WHERE document->>'resource'='account:self'");
    long version = ((Number) row.get("version")).longValue();
    String key = enforcementKey("block-policy", "policy", row.get("id").toString());
    String digest = "a".repeat(64);
    markEnforcement(
        key, String.format(java.util.Locale.ROOT, "%019d|true|%s", version + 1, digest));
    assertEquals(403, resource(tokens.access()).statusCode());
    markEnforcement(key, String.format(java.util.Locale.ROOT, "%019d|false|%s", version, digest));
    assertEquals(403, resource(tokens.access()).statusCode());
    markEnforcement(key, String.format(java.util.Locale.ROOT, "%019d|true|%s", version, digest));
    assertEquals(200, resource(tokens.access()).statusCode());
    resourcePolicy("DENY", List.of(), null);
    assertEquals(403, resource(tokens.access()).statusCode());
  }

  @Test
  void malformedAndWrongTypeRedisStateReturns503InsteadOfTreatingItAsMissing() throws Exception {
    Flow flow = flow(true);
    Pair tokens = initialTokens(flow);
    resourcePolicy("ALLOW", List.of(), null);
    String key = enforcementKey("block-policy", "identity", flow.userId().toString());
    markEnforcement(key, "not-a-watermark-synthetic");
    var unavailable = resource(tokens.access());
    assertEquals(503, unavailable.statusCode());
    assertEquals("{\"error\":\"temporarily_unavailable\"}", unavailable.body());
    assertFalse(unavailable.body().contains("synthetic"));
    redisEnforcement.delete(key);
    redisEnforcement.opsForList().leftPush(key, "synthetic");
    assertEquals(503, resource(tokens.access()).statusCode());
    redisEnforcement.delete(key);
    assertEquals(200, resource(tokens.access()).statusCode());
  }

  @Test
  void actualRedisTimeoutNeverFallsBackToSuccessfulLocalAuthorizationAndLaterRecovers()
      throws Exception {
    Flow flow = flow(true);
    Pair tokens = initialTokens(flow);
    resourcePolicy("ALLOW", List.of(), null);
    assertEquals(200, resource(tokens.access()).statusCode());
    assertEquals(
        0,
        REDIS
            .execInContainer("redis-cli", "-a", ROOT, "CLIENT", "PAUSE", "1500", "ALL")
            .getExitCode());
    var unavailable = resource(tokens.access());
    assertEquals(503, unavailable.statusCode(), unavailable.body());
    assertEquals("5", unavailable.headers().firstValue("Retry-After").orElseThrow());
    long timeout = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
    HttpResponse<String> recovered;
    do {
      Thread.sleep(100);
      recovered = resource(tokens.access());
    } while (recovered.statusCode() == 503 && System.nanoTime() < timeout);
    assertEquals(200, recovered.statusCode());
  }

  private HttpResponse<String> resource(String token) throws Exception {
    var request = HttpRequest.newBuilder(uri("/api/resources/account"));
    if (token != null) {
      request.header("Authorization", "Bearer " + token);
    }
    return HTTP.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> pam(String method, String path, String token, Object body)
      throws Exception {
    var request =
        HttpRequest.newBuilder(uri("/api/pam/requests" + path))
            .header("Content-Type", "application/json");
    if (token != null) {
      request.header("Authorization", "Bearer " + token);
    }
    return HTTP.send(
        request
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private void pamPolicy(String effect) throws Exception {
    var response =
        admin(
            "POST",
            "/api/admin/policies",
            Map.of(
                "name",
                "pam-" + UUID.randomUUID(),
                "priority",
                100,
                "enabled",
                true,
                "document",
                Map.of(
                    "schemaVersion",
                    1,
                    "resource",
                    "database:diagnostics",
                    "action",
                    "request",
                    "effect",
                    effect,
                    "rolesAnyOf",
                    List.of(),
                    "allowedScopes",
                    List.of())));
    assertEquals(201, response.statusCode(), response.body());
  }

  private record PamSession(Flow flow, HttpClient browser, Pair tokens) {}

  private PamSession pamSession() throws Exception {
    Flow flow = flow(true, false, true);
    HttpClient browser = mfaLogin(flow);
    String navigation =
        authorize(flow)
            .replace("&scope=" + enc("api:read"), "&scope=" + enc("pam:request pam:issue"));
    var page = get(browser, location(get(browser, navigation)));
    String code = approveScopes(browser, flow, page, List.of("pam:request", "pam:issue"));
    return new PamSession(flow, browser, pair(exchange(flow, code, flow.verifier(), CALLBACK)));
  }

  private void pamMfa(PamSession session) throws Exception {
    String seed = mfaSeed(mfaPost(session.browser(), "/mfa/enroll", Map.of("password", PASSWORD)));
    assertEquals(
        200,
        mfaPost(session.browser(), "/mfa/confirm", Map.of("code", mfaCode(seed))).statusCode());
  }

  private void pamIssuePolicy(String effect) throws Exception {
    var response =
        admin(
            "POST",
            "/api/admin/policies",
            Map.of(
                "name",
                "pam-issue-" + UUID.randomUUID(),
                "priority",
                100,
                "enabled",
                true,
                "document",
                Map.of(
                    "schemaVersion",
                    1,
                    "resource",
                    "database:diagnostics",
                    "action",
                    "issue",
                    "effect",
                    effect,
                    "rolesAnyOf",
                    List.of(),
                    "allowedScopes",
                    List.of())));
    assertEquals(201, response.statusCode());
  }

  private String pamRequest(PamSession session, int ttl) throws Exception {
    var response =
        pam(
            "POST",
            "",
            session.tokens().access(),
            Map.of("justification", "Investigar incidente PAM de teste", "ttlSeconds", ttl));
    assertEquals(201, response.statusCode());
    return "/" + JSON.readTree(response.body()).path("id").asText();
  }

  private void awaitPam(java.util.function.BooleanSupplier condition) throws Exception {
    long until = System.nanoTime() + Duration.ofSeconds(20).toNanos();
    while (!condition.getAsBoolean()) {
      assertTrue(System.nanoTime() < until, "PAM cleanup timed out");
      Thread.sleep(100);
    }
  }

  private boolean pamRoleExists(String username) {
    return jdbc.queryForObject(
        "SELECT EXISTS(SELECT 1 FROM pg_roles WHERE rolname=?)", Boolean.class, username);
  }

  private int pamFacts(String type, String id) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM iam_audit.security_events WHERE event_type=? AND details->>'requestId'=?",
        Integer.class,
        type,
        id);
  }

  @Test
  void pamAuthenticatedDenialsAreSanitizedAndAuditFailureRemainsClosed() throws Exception {
    var session = pamSession();
    String token = session.tokens().access();
    var body = Map.of("justification", "Investigar incidente privado", "ttlSeconds", 120);
    var denied = pam("POST", "", token, body);
    assertEquals(403, denied.statusCode());
    String correlation = denied.headers().firstValue("X-Correlation-ID").orElseThrow();
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM iam_audit.security_events WHERE event_type='pam.access.denied' AND actor=? AND correlation_id=? AND details->>'reason'='ACCESS_DENIED'",
            Integer.class,
            session.flow().userId().toString(),
            correlation));
    var invalid = pam("POST", "", "invalid-token", body);
    assertEquals(401, invalid.statusCode());
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM iam_audit.security_events WHERE event_type='pam.access.denied' AND correlation_id=?",
            Integer.class,
            invalid.headers().firstValue("X-Correlation-ID").orElseThrow()));
    pamPolicy("ALLOW");
    var mfa = pam("POST", "", token, body);
    assertEquals(403, mfa.statusCode());
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM iam_audit.security_events WHERE event_type='pam.access.denied' AND correlation_id=? AND details->>'reason'='MFA_REQUIRED'",
            Integer.class,
            mfa.headers().firstValue("X-Correlation-ID").orElseThrow()));
    org.mockito.Mockito.doThrow(new IllegalStateException("synthetic private audit failure"))
        .when(mfaAudit)
        .record(
            org.mockito.ArgumentMatchers.argThat(
                record -> record.type().value().equals("pam.access.denied")));
    try {
      assertEquals(503, pam("POST", "", token, body).statusCode());
    } finally {
      org.mockito.Mockito.reset(mfaAudit);
    }
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM iam_audit.security_events WHERE event_type='pam.access.denied' AND actor=? AND (details::text LIKE ? OR details::text LIKE '%Investigar%')",
            Integer.class, session.flow().userId().toString(), "%" + token + "%"));
  }

  @Test
  void pamExpirationAuditFailureRollsBackMarkerAndCanBeRetried() throws Exception {
    var session = pamSession();
    pamMfa(session);
    pamPolicy("ALLOW");
    pamIssuePolicy("ALLOW");
    String path = pamRequest(session, 30);
    String token = session.tokens().access();
    var issued = pam("POST", path + "/credentials", token, null);
    assertEquals(201, issued.statusCode());
    String correlation = issued.headers().firstValue("X-Correlation-ID").orElseThrow();
    var secret = JSON.readTree(issued.body());
    String id = path.substring(1);
    org.mockito.Mockito.doThrow(new IllegalStateException("synthetic expiry audit failure"))
        .when(mfaAudit)
        .record(
            org.mockito.ArgumentMatchers.argThat(
                record -> record.type().value().equals("pam.credential.expired")));
    org.mockito.Mockito.doReturn(
            java.time.Instant.parse(secret.path("expiresAt").asText()).plusSeconds(1))
        .when(riskClock)
        .instant();
    try {
      assertThrows(RuntimeException.class, pamCredentials::maintain);
      assertNull(
          jdbc.queryForObject(
              "SELECT expiration_recorded_at FROM iam_authorization.privileged_issuances WHERE request_id=?",
              java.sql.Timestamp.class,
              UUID.fromString(id)));
      assertEquals(0, pamFacts("pam.credential.expired", id));
    } finally {
      org.mockito.Mockito.reset(mfaAudit);
    }
    org.mockito.Mockito.doReturn(
            java.time.Instant.parse(secret.path("expiresAt").asText()).plusSeconds(10))
        .when(riskClock)
        .instant();
    pamCredentials.maintain();
    pamCredentials.maintain();
    awaitPam(() -> pamFacts("pam.credential.revoked", id) == 1);
    assertEquals(1, pamFacts("pam.credential.expired", id));
    assertEquals(
        correlation,
        jdbc.queryForObject(
            "SELECT correlation_id FROM iam_audit.security_events WHERE event_type='pam.credential.expired' AND details->>'requestId'=?",
            String.class,
            id));
    assertEquals(
        correlation,
        jdbc.queryForObject(
            "SELECT correlation_id FROM iam_audit.security_events WHERE event_type='pam.credential.revoked' AND details->>'requestId'=?",
            String.class,
            id));
  }

  @Test
  void pamAppRoleCannotReadRuntimeKvTransitOrRevokeOutsideItsPrefix() throws Exception {
    String token =
        vault(
                "auth/approle/login",
                Map.of(
                    "role_id",
                    Files.readString(credentials.resolve("pam-role")),
                    "secret_id",
                    Files.readString(credentials.resolve("pam-secret"))))
            .path("auth")
            .path("client_token")
            .asText();
    try {
      for (String path :
          List.of(
              "database/creds/iam-runtime",
              "secret/data/securegate/iam-core",
              "transit/keys/securegate-jwt",
              "pam-database/config/securegate-pam")) {
        var response =
            HTTP.send(
                HttpRequest.newBuilder(URI.create(vaultAddress() + "/v1/" + path))
                    .header("X-Vault-Token", token)
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(403, response.statusCode());
      }
      for (String path :
          List.of(
              "sys/leases/revoke/database/creds/iam-runtime/synthetic",
              "sys/leases/revoke/pam-database/creds/diagnostics-10/synthetic")) {
        var response =
            HTTP.send(
                HttpRequest.newBuilder(URI.create(vaultAddress() + "/v1/" + path))
                    .header("X-Vault-Token", token)
                    .header("Content-Type", "application/json")
                    .POST(
                        HttpRequest.BodyPublishers.ofString(
                            "{\"lease_id\":\"other/lease\",\"sync\":true}"))
                    .build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(403, response.statusCode());
      }
    } finally {
      HTTP.send(
          HttpRequest.newBuilder(URI.create(vaultAddress() + "/v1/auth/token/revoke-self"))
              .header("X-Vault-Token", token)
              .POST(HttpRequest.BodyPublishers.ofString("{}"))
              .build(),
          HttpResponse.BodyHandlers.discarding());
    }
  }

  @Test
  void pamCredentialsAreDeliveredOnceWithNarrowDatabasePermissionsAndAdministrativeRevocation()
      throws Exception {
    var session = pamSession();
    pamMfa(session);
    pamPolicy("ALLOW");
    String path = pamRequest(session, 120);
    String token = session.tokens().access();
    assertEquals(403, pam("POST", path + "/credentials", token, null).statusCode());
    pamIssuePolicy("ALLOW");
    java.util.List<HttpResponse<String>> responses;
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var first = executor.submit(() -> pam("POST", path + "/credentials", token, null));
      var second = executor.submit(() -> pam("POST", path + "/credentials", token, null));
      responses = List.of(first.get(), second.get());
    }
    assertEquals(
        java.util.Set.of(201, 409),
        responses.stream()
            .map(HttpResponse::statusCode)
            .collect(java.util.stream.Collectors.toSet()));
    var delivered = responses.stream().filter(r -> r.statusCode() == 201).findFirst().orElseThrow();
    assertEquals("no-store", delivered.headers().firstValue("Cache-Control").orElseThrow());
    var secret = JSON.readTree(delivered.body());
    String username = secret.path("username").asText();
    String password = secret.path("password").asText();
    assertTrue(username.matches("pam_[A-Za-z0-9]{20}"));
    try (var connection =
            java.sql.DriverManager.getConnection(DATABASE.getJdbcUrl(), username, password);
        var statement = connection.createStatement()) {
      try (var rows =
          statement.executeQuery("SELECT * FROM securegate_diagnostics.database_health")) {
        assertTrue(rows.next());
      }
      assertEquals(
          "42501",
          assertThrows(
                  java.sql.SQLException.class,
                  () -> statement.executeQuery("SELECT * FROM iam_identity.users"))
              .getSQLState());
      assertEquals(
          "42501",
          assertThrows(
                  java.sql.SQLException.class, () -> statement.execute("SET ROLE browser_test"))
              .getSQLState());
      assertEquals(
          "42501",
          assertThrows(
                  java.sql.SQLException.class,
                  () ->
                      statement.execute(
                          "CREATE TABLE securegate_diagnostics.forbidden(id integer)"))
              .getSQLState());
      var metadata = pam("GET", path + "/credentials", token, null);
      assertEquals(200, metadata.statusCode());
      assertFalse(metadata.body().contains(password));
      assertFalse(metadata.body().contains(username));
      var other = pamSession();
      pamMfa(other);
      assertEquals(
          404, pam("POST", path + "/credentials", other.tokens().access(), null).statusCode());
      assertEquals(
          404, pam("GET", path + "/credentials", other.tokens().access(), null).statusCode());
      assertEquals(
          0,
          jdbc.queryForObject(
              "SELECT count(*) FROM iam_audit.outbox_events WHERE payload::text LIKE ?",
              Integer.class,
              "%" + password + "%"));
      pamIssuePolicy("DENY");
      assertEquals(403, pam("POST", path + "/credentials", token, null).statusCode());
      var revoked = admin("POST", "/api/admin/pam/requests" + path + "/revoke", Map.of());
      assertEquals(202, revoked.statusCode());
      awaitPam(() -> !pamRoleExists(username));
      assertThrows(java.sql.SQLException.class, () -> statement.executeQuery("SELECT 1"));
    }
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM iam_audit.outbox_events WHERE event_type='pam.credential.issued' AND payload->'data'->>'requestId'=?",
            Integer.class,
            path.substring(1)));
  }

  @Test
  void pamCredentialFailureIsConsumedAndNeverReturnsASecondSecret() throws Exception {
    var session = pamSession();
    pamMfa(session);
    pamPolicy("ALLOW");
    pamIssuePolicy("ALLOW");
    String path = pamRequest(session, 120);
    String token = session.tokens().access();
    org.mockito.Mockito.doThrow(new IllegalStateException("synthetic issuance audit failure"))
        .when(mfaAudit)
        .record(
            org.mockito.ArgumentMatchers.argThat(
                record -> record.type().value().equals("pam.credential.issued")));
    try {
      assertEquals(503, pam("POST", path + "/credentials", token, null).statusCode());
    } finally {
      org.mockito.Mockito.reset(mfaAudit);
    }
    assertEquals(409, pam("POST", path + "/credentials", token, null).statusCode());
    awaitPam(
        () ->
            "REVOKED"
                .equals(
                    jdbc.queryForObject(
                        "SELECT state FROM iam_authorization.privileged_issuances WHERE request_id=?",
                        String.class,
                        UUID.fromString(path.substring(1)))));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM iam_audit.outbox_events WHERE event_type='pam.credential.issued' AND payload->'data'->>'requestId'=?",
            Integer.class,
            path.substring(1)));
  }

  @Test
  void pamExpiryDuringVaultOutageKeepsCleanupPendingThenTerminatesExistingSessions()
      throws Exception {
    var session = pamSession();
    pamMfa(session);
    pamPolicy("ALLOW");
    pamIssuePolicy("ALLOW");
    String path = pamRequest(session, 30);
    String token = session.tokens().access();
    var issued = pam("POST", path + "/credentials", token, null);
    assertEquals(201, issued.statusCode());
    var secret = JSON.readTree(issued.body());
    String issueCorrelation = issued.headers().firstValue("X-Correlation-ID").orElseThrow();
    String username = secret.path("username").asText();
    try (var connection =
            java.sql.DriverManager.getConnection(
                DATABASE.getJdbcUrl(), username, secret.path("password").asText());
        var statement = connection.createStatement()) {
      VAULT.getDockerClient().pauseContainerCmd(VAULT.getContainerId()).exec();
      try {
        long until = System.nanoTime() + Duration.ofSeconds(12).toNanos();
        while (System.nanoTime() < until) {
          statement.executeQuery("SELECT 1").close();
          Thread.sleep(200);
        }
        // PostgreSQL VALID UNTIL does not end an already authenticated active session.
        assertTrue(pamRoleExists(username));
        awaitPam(() -> pamFacts("pam.credential.expired", path.substring(1)) == 1);
        assertEquals(0, pamFacts("pam.credential.revoked", path.substring(1)));
        assertEquals(
            issueCorrelation,
            jdbc.queryForObject(
                "SELECT correlation_id FROM iam_audit.security_events WHERE event_type='pam.credential.expired' AND details->>'requestId'=?",
                String.class,
                path.substring(1)));
        assertThrows(
            java.sql.SQLException.class,
            () ->
                java.sql.DriverManager.getConnection(
                    DATABASE.getJdbcUrl(), username, secret.path("password").asText()));
      } finally {
        VAULT.getDockerClient().unpauseContainerCmd(VAULT.getContainerId()).exec();
      }
      awaitPam(() -> !pamRoleExists(username));
      assertThrows(java.sql.SQLException.class, () -> statement.executeQuery("SELECT 1"));
    }
    awaitPam(
        () ->
            "REVOKED"
                .equals(
                    jdbc.queryForObject(
                        "SELECT state FROM iam_authorization.privileged_issuances WHERE request_id=?",
                        String.class,
                        UUID.fromString(path.substring(1)))));
  }

  @Test
  void pamRequiresOwnScopeLivePolicyAndRealMfaThenRevokesWithAtomicAudit() throws Exception {
    var session = pamSession();
    String token = session.tokens().access();
    var body = Map.of("justification", "Diagnosticar incidente de teste", "ttlSeconds", 120);
    assertEquals(401, pam("POST", "", null, body).statusCode());
    assertEquals(401, pam("POST", "", ADMIN, body).statusCode());
    assertEquals(403, pam("POST", "", token, body).statusCode());
    pamPolicy("ALLOW");
    assertEquals(
        "mfa_required", JSON.readTree(pam("POST", "", token, body).body()).path("error").asText());
    pamMfa(session);
    assertEquals(
        400,
        pam("POST", "", token, Map.of("justification", "curta", "ttlSeconds", 301)).statusCode());
    var created = pam("POST", "", token, body);
    assertEquals(201, created.statusCode(), created.body());
    assertEquals("no-store", created.headers().firstValue("Cache-Control").orElseThrow());
    var record = JSON.readTree(created.body());
    String path = "/" + record.path("id").asText();
    assertEquals("AUTHORIZED", record.path("status").asText());
    assertEquals("database:diagnostics", record.path("target").asText());
    assertFalse(created.body().contains("justification"));
    assertFalse(created.body().contains("password"));
    assertEquals(200, pam("GET", path, token, null).statusCode());
    var other = pamSession();
    pamMfa(other);
    assertEquals(404, pam("GET", path, other.tokens().access(), null).statusCode());
    assertEquals(404, pam("DELETE", path, other.tokens().access(), null).statusCode());
    assertEquals(
        "REVOKED", JSON.readTree(pam("DELETE", path, token, null).body()).path("status").asText());
    assertEquals(
        "REVOKED", JSON.readTree(pam("DELETE", path, token, null).body()).path("status").asText());
    assertEquals(
        2,
        jdbc.queryForObject(
            "SELECT count(*) FROM iam_audit.outbox_events WHERE event_type IN ('pam.request.authorized','pam.request.revoked') AND payload->'data'->>'requestId'=?",
            Integer.class,
            record.path("id").asText()));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM iam_audit.security_events WHERE event_type LIKE 'pam.%' AND details::text LIKE '%Diagnosticar%'",
            Integer.class));
    pamPolicy("DENY");
    assertEquals(403, pam("POST", "", token, body).statusCode());
    assertEquals(403, pam("GET", path, token, null).statusCode());
  }

  @Test
  void pamAuditFailureRollsBackRequestAndExpirationCannotBeExtendedByReads() throws Exception {
    var session = pamSession();
    pamMfa(session);
    pamPolicy("ALLOW");
    String token = session.tokens().access();
    org.mockito.Mockito.doThrow(new IllegalStateException("synthetic audit failure"))
        .when(mfaAudit)
        .record(
            org.mockito.ArgumentMatchers.argThat(
                record -> record.type().value().equals("pam.request.authorized")));
    int before =
        jdbc.queryForObject(
            "SELECT count(*) FROM iam_authorization.privileged_requests", Integer.class);
    var body = Map.of("justification", "Investigar incidente de teste", "ttlSeconds", 30);
    try {
      assertEquals(500, pam("POST", "", token, body).statusCode());
      assertEquals(
          before,
          jdbc.queryForObject(
              "SELECT count(*) FROM iam_authorization.privileged_requests", Integer.class));
    } finally {
      org.mockito.Mockito.reset(mfaAudit);
    }
    var created = pam("POST", "", token, body);
    assertEquals(201, created.statusCode(), created.body());
    var record = JSON.readTree(created.body());
    String path = "/" + record.path("id").asText();
    org.mockito.Mockito.doReturn(java.time.Instant.parse(record.path("expiresAt").asText()))
        .when(riskClock)
        .instant();
    var expired = pam("GET", path, token, null);
    assertEquals(200, expired.statusCode(), expired.body());
    assertEquals("EXPIRED", JSON.readTree(expired.body()).path("status").asText());
    assertEquals(record.path("expiresAt"), JSON.readTree(expired.body()).path("expiresAt"));
    assertEquals(
        "EXPIRED", JSON.readTree(pam("DELETE", path, token, null).body()).path("status").asText());
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var first = executor.submit(pamRequests::observeExpirations);
      var second = executor.submit(pamRequests::observeExpirations);
      first.get();
      second.get();
    }
    assertEquals(1, pamFacts("pam.request.expired", record.path("id").asText()));
    assertEquals(
        created.headers().firstValue("X-Correlation-ID").orElseThrow(),
        jdbc.queryForObject(
            "SELECT correlation_id FROM iam_audit.security_events WHERE event_type='pam.request.expired' AND details->>'requestId'=?",
            String.class,
            record.path("id").asText()));
  }

  private void resourcePolicy(String effect, List<String> scopes, Integer ttl) throws Exception {
    var rule = new HashMap<String, Object>();
    rule.put("schemaVersion", 1);
    rule.put("resource", "account:self");
    rule.put("action", "read");
    rule.put("effect", effect);
    rule.put("rolesAnyOf", List.of());
    rule.put("allowedScopes", scopes);
    if (ttl != null) {
      rule.put("maxAccessTokenTtlSeconds", ttl);
    }
    var response =
        admin(
            "POST",
            "/api/admin/policies",
            Map.of(
                "name",
                "resource-" + UUID.randomUUID(),
                "priority",
                100,
                "enabled",
                true,
                "document",
                rule));
    assertEquals(201, response.statusCode(), response.body());
  }

  @Test
  void resourceRequiresBearerAndOwnPolicyThenReevaluatesDeny() throws Exception {
    Flow flow = flow(true);
    Pair tokens = initialTokens(flow);
    assertEquals(401, resource(null).statusCode());
    assertEquals(401, resource("bad-token").statusCode());
    assertEquals(403, resource(tokens.access()).statusCode());
    resourcePolicy("ALLOW_LIMITED", List.of("api:read"), 60);
    var response = resource(tokens.access());
    assertEquals(200, response.statusCode(), response.body());
    var body = JSON.readTree(response.body());
    assertEquals(flow.userId().toString(), body.path("sub").asText());
    assertEquals("api:read", body.path("scope").get(0).asText());
    assertEquals(3, body.size());
    assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
    assertTrue(response.headers().allValues("Set-Cookie").isEmpty());
    assertEquals(401, get(mfaLogin(flow), "/api/resources/account").statusCode());
    var unsupported =
        HTTP.send(
            HttpRequest.newBuilder(uri("/api/resources/account"))
                .header("Authorization", "Bearer " + tokens.access())
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(403, unsupported.statusCode());
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM iam_audit.security_events WHERE event_type='resource.access.allowed' AND actor=? AND resource='account:self' AND details->>'action'='read'",
            Integer.class,
            flow.userId().toString()));
    resourcePolicy("DENY", List.of(), null);
    assertEquals(403, resource(tokens.access()).statusCode());
  }

  @Test
  void resourceUsesCurrentPolicyTtlWithoutSlidingTheTokenAge() throws Exception {
    Flow flow = flow(true);
    Pair tokens = initialTokens(flow);
    resourcePolicy("ALLOW_LIMITED", List.of("api:read"), 30);
    assertEquals(200, resource(tokens.access()).statusCode());
    var issued =
        com.nimbusds.jwt.SignedJWT.parse(tokens.access())
            .getJWTClaimsSet()
            .getIssueTime()
            .toInstant();
    org.mockito.Mockito.doReturn(issued.plusSeconds(30)).when(riskClock).instant();
    assertEquals(403, resource(tokens.access()).statusCode());
  }

  @Test
  void resourceSeesRevocationRefreshReplacementAndIdentityVersionImmediately() throws Exception {
    Flow flow = flow(true);
    Pair tokens = initialTokens(flow);
    resourcePolicy("ALLOW", List.of(), null);
    assertEquals(200, resource(tokens.access()).statusCode());
    var renewed = JSON.readTree(refresh(flow, tokens.refresh(), null).body());
    String access = renewed.path("access_token").asText();
    assertFalse(access.isBlank());
    assertEquals(401, resource(tokens.access()).statusCode());
    assertEquals(200, resource(access).statusCode());
    assertEquals(200, oauthPost("/oauth2/revoke", flow, Map.of("token", access)).statusCode());
    assertEquals(401, resource(access).statusCode());
    Flow other = flow(true);
    Pair otherTokens = initialTokens(other);
    assertEquals(200, resource(otherTokens.access()).statusCode());
    jdbc.update("UPDATE iam_identity.users SET version=version+1 WHERE id=?", other.userId());
    assertEquals(401, resource(otherTokens.access()).statusCode());
  }

  @Test
  void resourceMfaCannotUseProofFromAnotherLoginAndLogoutRemovesProof() throws Exception {
    Flow flow = flow(true);
    HttpClient browser = mfaLogin(flow);
    String code = approve(browser, flow, get(browser, location(get(browser, authorize(flow)))));
    var tokens = pair(exchange(flow, code, flow.verifier(), CALLBACK));
    resourcePolicy("ALLOW_WITH_MFA", List.of(), null);
    assertEquals(
        "mfa_required", JSON.readTree(resource(tokens.access()).body()).path("error").asText());
    HttpClient other = mfaLogin(flow);
    String seed = mfaSeed(mfaPost(other, "/mfa/enroll", Map.of("password", PASSWORD)));
    var activated = mfaPost(other, "/mfa/confirm", Map.of("code", mfaCode(seed)));
    var recovery = mfaRecovery(activated);
    assertEquals(403, resource(tokens.access()).statusCode());
    assertEquals(
        200,
        mfaPost(browser, "/mfa/verify", Map.of("code", recovery.getFirst(), "method", "RECOVERY"))
            .statusCode());
    assertEquals(200, resource(tokens.access()).statusCode());
    var page = get(browser, "/mfa");
    post(browser, "/logout", Map.of("_csrf", hidden(page.body(), "_csrf")));
    assertEquals(403, resource(tokens.access()).statusCode());
  }

  @Test
  void resourceRejectsIdTokensWrongAudienceAndTamperedTokens() throws Exception {
    Flow flow = flow(false, true);
    var browser = browser();
    String code =
        approveScopes(browser, flow, pending(browser, flow), List.of("openid", "api:read"));
    var tokens = JSON.readTree(exchange(flow, code, flow.verifier(), CALLBACK).body());
    resourcePolicy("ALLOW", List.of(), null);
    String access = tokens.path("access_token").asText();
    assertEquals(200, resource(access).statusCode());
    assertEquals(401, resource(tokens.path("id_token").asText()).statusCode());
    String[] parts = access.split("\\.");
    parts[1] =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString("{\"sub\":\"forged\"}".getBytes(StandardCharsets.UTF_8));
    assertEquals(401, resource(String.join(".", parts)).statusCode());
    // A valid signature and stored authorization still do not substitute for the resource audience.
    assertTrue(resourceTokens.resolve(access, "foreign-api").isEmpty());
  }

  @Test
  void resourcePolicyDependencyFailureReturns503WithoutLeakingDetails() throws Exception {
    Flow flow = flow(true);
    Pair tokens = initialTokens(flow);
    org.mockito.Mockito.doThrow(new IllegalStateException("synthetic-secret-detail"))
        .when(enforcementPolicies)
        .decide(
            org.mockito.ArgumentMatchers.eq(flow.userId()),
            org.mockito.ArgumentMatchers.eq("account:self"),
            org.mockito.ArgumentMatchers.eq("read"),
            org.mockito.ArgumentMatchers.anySet(),
            org.mockito.ArgumentMatchers.any());
    var response = resource(tokens.access());
    assertEquals(503, response.statusCode(), response.body());
    assertEquals("{\"error\":\"temporarily_unavailable\"}", response.body());
    assertEquals("5", response.headers().firstValue("Retry-After").orElseThrow());
    org.mockito.Mockito.reset(enforcementPolicies);
    resourcePolicy("ALLOW", List.of(), null);
    org.mockito.Mockito.doThrow(new IllegalStateException("synthetic-audit-detail"))
        .when(mfaAudit)
        .record(
            org.mockito.ArgumentMatchers.argThat(
                event -> event.type().value().equals("resource.access.allowed")));
    assertEquals(503, resource(tokens.access()).statusCode());
  }

  @Test
  void authorizationChallengesBeforeConsentAndResumesOidcWithOriginalPkceStateAndNonce()
      throws Exception {
    Flow flow = flow(false, true);
    issuancePolicy(flow, "ALLOW_WITH_MFA", List.of(), null, List.of(), true);
    HttpClient browser = mfaLogin(flow);
    var challenge = get(browser, authorize(flow));
    assertEquals(302, challenge.statusCode());
    assertEquals("/mfa", location(challenge));
    assertEquals(0, authorizationRows(flow));
    var page = get(browser, "/mfa");
    assertTrue(page.body().contains("Verificação adicional"));
    assertFalse(page.body().contains("<script>alert"));
    assertFalse(page.body().contains("name=\"ticket\""));
    String seed = mfaSeed(mfaPost(browser, "/mfa/enroll", Map.of("password", PASSWORD)));
    var activated = mfaPost(browser, "/mfa/confirm", Map.of("code", mfaCode(seed)));
    assertEquals(10, mfaRecovery(activated).size());
    assertTrue(activated.body().contains("Continuar autorização"));
    String ticket = hidden(activated.body(), "ticket");
    assertEquals(403, post(browser, "/oauth2/continue", Map.of("ticket", ticket)).statusCode());
    var resumed =
        post(
            browser,
            "/oauth2/continue",
            Map.of(
                "ticket",
                ticket,
                "return_to",
                "https://attacker.example/",
                "_csrf",
                hidden(activated.body(), "_csrf")));
    assertEquals(303, resumed.statusCode());
    assertTrue(location(resumed).startsWith("/oauth2/authorize?"));
    assertEquals(flow.state(), query(location(resumed)).get("state"));
    assertEquals(flow.state(), query(location(resumed)).get("nonce"));
    assertEquals(
        query(authorize(flow)).get("code_challenge"),
        query(location(resumed)).get("code_challenge"));
    var consent = get(browser, location(resumed));
    assertEquals(302, consent.statusCode());
    assertTrue(location(consent).contains("/oauth2/consent"));
    var approval = get(browser, location(consent));
    assertEquals(200, approval.statusCode());
    String code = approveScopes(browser, flow, approval, List.of("openid", "api:read"));
    var tokens = exchange(flow, code, flow.verifier(), CALLBACK);
    assertEquals(200, tokens.statusCode(), tokens.body());
    assertEquals(
        flow.state(),
        com.nimbusds.jwt.SignedJWT.parse(JSON.readTree(tokens.body()).path("id_token").asText())
            .getJWTClaimsSet()
            .getStringClaim("nonce"));
    assertEquals(
        400,
        post(
                browser,
                "/oauth2/continue",
                Map.of("ticket", ticket, "_csrf", hidden(get(browser, "/mfa").body(), "_csrf")))
            .statusCode());
  }

  @Test
  void mfaRequirementAddedDuringConsentChallengesWithoutRecordingApproval() throws Exception {
    Flow flow = flow();
    HttpClient browser = browser();
    var page = pending(browser, flow);
    String oldState = hidden(page.body(), "state");
    issuancePolicy(flow, "ALLOW_WITH_MFA", List.of(), null, List.of(), true);
    assertEquals(
        403,
        post(
                browser,
                "/oauth2/authorize",
                Map.of("client_id", flow.clientId(), "state", oldState, "scope", "api:read"))
            .statusCode());
    assertFalse(get(browser, "/mfa").body().contains("Verificação adicional"));
    var challenge = navigationConsent(browser, flow, page, List.of("api:read"));
    assertEquals("/mfa", location(challenge));
    assertNull(
        authorizations.findByToken(
            oldState,
            new org.springframework.security.oauth2.server.authorization.OAuth2TokenType("state")));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM iam_authorization.oauth2_authorization_consent WHERE principal_name=?",
            Integer.class,
            flow.userId().toString()));
    String seed = mfaSeed(mfaPost(browser, "/mfa/enroll", Map.of("password", PASSWORD)));
    var activated = mfaPost(browser, "/mfa/confirm", Map.of("code", mfaCode(seed)));
    var resumed = continueAuthorization(browser, activated);
    var consent = get(browser, location(resumed));
    var newPage = get(browser, location(consent));
    assertNotEquals(oldState, hidden(newPage.body(), "state"));
    var denied = navigationConsent(browser, flow, newPage, List.of());
    assertEquals("access_denied", query(location(denied)).get("error"));
    assertEquals(flow.state(), query(location(denied)).get("state"));
  }

  @Test
  void proofExpiringWhileConsentIsDisplayedRequiresAnotherVerification() throws Exception {
    Flow flow = flow();
    HttpClient browser = mfaLogin(flow);
    java.time.Instant now =
        java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    org.mockito.Mockito.doReturn(now).when(riskClock).instant();
    String seed = mfaSeed(mfaPost(browser, "/mfa/enroll", Map.of("password", PASSWORD)));
    var recovery = mfaRecovery(mfaPost(browser, "/mfa/confirm", Map.of("code", mfaCode(seed))));
    issuancePolicy(flow, "ALLOW_WITH_MFA", List.of(), null, List.of(), true);
    var consent = get(browser, authorize(flow));
    var page = get(browser, location(consent));
    org.mockito.Mockito.doReturn(now.plusSeconds(301)).when(riskClock).instant();
    assertEquals("/mfa", location(navigationConsent(browser, flow, page, List.of("api:read"))));
    var verified =
        mfaPost(browser, "/mfa/verify", Map.of("method", "RECOVERY", "code", recovery.getFirst()));
    assertEquals(303, continueAuthorization(browser, verified).statusCode());
  }

  @Test
  void activeFactorCanResumeWithRecoveryAndPendingChallengeHasAbsoluteExpiry() throws Exception {
    Flow flow = flow();
    HttpClient browser = mfaLogin(flow);
    java.time.Instant now =
        java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    org.mockito.Mockito.doReturn(now).when(riskClock).instant();
    String seed = mfaSeed(mfaPost(browser, "/mfa/enroll", Map.of("password", PASSWORD)));
    List<String> recovery =
        mfaRecovery(mfaPost(browser, "/mfa/confirm", Map.of("code", mfaCode(seed))));
    issuancePolicy(flow, "ALLOW_WITH_MFA", List.of(), null, List.of(), true);
    org.mockito.Mockito.doReturn(now.plusSeconds(301)).when(riskClock).instant();
    assertEquals("/mfa", location(get(browser, authorize(flow))));
    var verified =
        mfaPost(browser, "/mfa/verify", Map.of("method", "RECOVERY", "code", recovery.getFirst()));
    assertTrue(verified.body().contains("Continuar autorização"));
    String ticket = hidden(verified.body(), "ticket");
    org.mockito.Mockito.doReturn(now.plusSeconds(602)).when(riskClock).instant();
    assertFalse(get(browser, "/mfa").body().contains("name=\"ticket\""));
    assertEquals(
        400,
        post(
                browser,
                "/oauth2/continue",
                Map.of("ticket", ticket, "_csrf", hidden(get(browser, "/mfa").body(), "_csrf")))
            .statusCode());
    assertEquals("/mfa", location(get(browser, authorize(flow))));
    verified =
        mfaPost(browser, "/mfa/verify", Map.of("method", "RECOVERY", "code", recovery.get(1)));
    assertEquals(303, continueAuthorization(browser, verified).statusCode());
  }

  @Test
  void continuationReevaluatesPolicyAndCannotBypassDenyIntroducedDuringMfa() throws Exception {
    Flow flow = flow();
    issuancePolicy(flow, "ALLOW_WITH_MFA", List.of(), null, List.of(), true);
    HttpClient browser = mfaLogin(flow);
    assertEquals("/mfa", location(get(browser, authorize(flow))));
    String seed = mfaSeed(mfaPost(browser, "/mfa/enroll", Map.of("password", PASSWORD)));
    var activated = mfaPost(browser, "/mfa/confirm", Map.of("code", mfaCode(seed)));
    issuancePolicy(flow, "DENY", List.of(), null, List.of(), true);
    var resumed = continueAuthorization(browser, activated);
    var denied = get(browser, location(resumed));
    assertEquals("access_denied", query(location(denied)).get("error"));
    assertEquals(flow.state(), query(location(denied)).get("state"));
    assertEquals(0, authorizationRows(flow));
  }

  @Test
  void continuationIsBoundToBrowserAndClearedOnReauthentication() throws Exception {
    Flow flow = flow();
    issuancePolicy(flow, "ALLOW_WITH_MFA", List.of(), null, List.of(), true);
    HttpClient browser = mfaLogin(flow);
    get(browser, authorize(flow));
    String seed = mfaSeed(mfaPost(browser, "/mfa/enroll", Map.of("password", PASSWORD)));
    var activated = mfaPost(browser, "/mfa/confirm", Map.of("code", mfaCode(seed)));
    String ticket = hidden(activated.body(), "ticket");
    HttpClient other = mfaLogin(flow);
    assertEquals(
        400,
        post(
                other,
                "/oauth2/continue",
                Map.of("ticket", ticket, "_csrf", hidden(get(other, "/mfa").body(), "_csrf")))
            .statusCode());
    assertEquals(
        400,
        post(
                browser,
                "/oauth2/continue",
                Map.of(
                    "ticket",
                    UUID.randomUUID().toString(),
                    "_csrf",
                    hidden(get(browser, "/mfa").body(), "_csrf")))
            .statusCode());
    var login = get(browser, "/login");
    assertEquals(
        302,
        post(
                browser,
                "/login",
                Map.of(
                    "username",
                    flow.email(),
                    "password",
                    PASSWORD,
                    "_csrf",
                    hidden(login.body(), "_csrf")))
            .statusCode());
    assertEquals(
        400,
        post(
                browser,
                "/oauth2/continue",
                Map.of("ticket", ticket, "_csrf", hidden(get(browser, "/mfa").body(), "_csrf")))
            .statusCode());
  }

  @Test
  void promptNoneNeverCreatesMfaChallengeAndMalformedRedirectRemainsLocal() throws Exception {
    Flow flow = flow(false, true);
    issuancePolicy(flow, "ALLOW_WITH_MFA", List.of(), null, List.of(), true);
    HttpClient browser = mfaLogin(flow);
    var denied = get(browser, authorize(flow) + "&prompt=none");
    assertEquals("interaction_required", query(location(denied)).get("error"));
    assertEquals(flow.state(), query(location(denied)).get("state"));
    assertFalse(get(browser, "/mfa").body().contains("Verificação adicional"));
    assertEquals(0, authorizationRows(flow));
    var invalid =
        get(
            browser,
            authorize(flow).replace(enc(CALLBACK), enc("https://attacker.example/callback")));
    assertEquals(400, invalid.statusCode());
    assertTrue(invalid.headers().firstValue("Location").isEmpty());
    var anonymous = get(browser(), authorize(flow) + "&prompt=none");
    assertEquals("login_required", query(location(anonymous)).get("error"));
  }

  @Test
  void userCanDenyConsentWithoutCompletingNewMfaRequirement() throws Exception {
    Flow flow = flow();
    HttpClient browser = browser();
    var page = pending(browser, flow);
    issuancePolicy(flow, "ALLOW_WITH_MFA", List.of(), null, List.of(), true);
    var denied = navigationConsent(browser, flow, page, List.of());
    assertEquals("access_denied", query(location(denied)).get("error"));
    assertFalse(get(browser, "/mfa").body().contains("Verificação adicional"));
  }

  @Test
  void browserPolicyFailureReturns503WithoutCreatingConsentOrContinuation() throws Exception {
    Flow flow = flow();
    HttpClient browser = mfaLogin(flow);
    org.mockito.Mockito.doThrow(new IllegalStateException("synthetic navigation failure"))
        .when(enforcementPolicies)
        .decide(
            org.mockito.ArgumentMatchers.eq(flow.userId()),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anySet(),
            org.mockito.ArgumentMatchers.any());
    var failed = get(browser, authorize(flow));
    assertEquals(503, failed.statusCode());
    assertTrue(failed.headers().firstValue("Location").isEmpty());
    assertFalse(failed.body().contains("synthetic"));
    assertEquals(0, authorizationRows(flow));
    org.mockito.Mockito.reset(enforcementPolicies);
    assertTrue(location(get(browser, authorize(flow))).contains("/oauth2/consent"));
  }

  private int authorizationRows(Flow flow) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM iam_authorization.oauth2_authorization a JOIN iam_authorization.oauth2_registered_client c ON c.id=a.registered_client_id WHERE c.client_id=?",
        Integer.class,
        flow.clientId());
  }

  private HttpResponse<String> continueAuthorization(HttpClient browser, HttpResponse<String> page)
      throws Exception {
    return post(
        browser,
        "/oauth2/continue",
        Map.of("ticket", hidden(page.body(), "ticket"), "_csrf", hidden(page.body(), "_csrf")));
  }

  private HttpResponse<String> navigationConsent(
      HttpClient browser, Flow flow, HttpResponse<String> page, List<String> scopes)
      throws Exception {
    String values =
        form(
                Map.of(
                    "client_id",
                    flow.clientId(),
                    "state",
                    hidden(page.body(), "state"),
                    "_csrf",
                    hidden(page.body(), "_csrf")))
            + scopes.stream()
                .map(scope -> "&scope=" + enc(scope))
                .collect(java.util.stream.Collectors.joining());
    return browser.send(
        HttpRequest.newBuilder(uri("/oauth2/authorize"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(values))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
  com.securegate.iam.policy.application.port.PolicyDecisions enforcementPolicies;

  @Test
  void tokenPolicyDefaultsToDenyAndReevaluatesTheSameUnconsumedCode() throws Exception {
    Flow flow = flow();
    HttpClient browser = browser();
    String code = approve(browser, flow, pending(browser, flow));
    issuancePolicy(flow, "ALLOW", List.of(), null, List.of(), false);
    assertError(exchange(flow, code, flow.verifier(), CALLBACK), "invalid_grant");
    assertTrue(
        authorizations
            .findByToken(
                code,
                new org.springframework.security.oauth2.server.authorization.OAuth2TokenType(
                    "code"))
            .getToken(
                org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode
                    .class)
            .isActive());
    issuancePolicy(flow, "DENY", List.of(), null, List.of(), true);
    assertError(exchange(flow, code, flow.verifier(), CALLBACK), "invalid_grant");
    issuancePolicy(flow, "ALLOW", List.of(), null, List.of(), true);
    assertEquals(200, exchange(flow, code, flow.verifier(), CALLBACK).statusCode());
  }

  @Test
  void limitedPolicyNarrowsJwtResponsePersistenceAndOidcClaims() throws Exception {
    Flow flow = flow(false, true);
    issuancePolicy(flow, "ALLOW_LIMITED", List.of("openid", "api:read"), 60, List.of(), true);
    HttpClient browser = browser();
    String code =
        approveScopes(
            browser,
            flow,
            pending(browser, flow),
            List.of("openid", "profile", "email", "api:read"));
    var response = exchange(flow, code, flow.verifier(), CALLBACK);
    assertEquals(200, response.statusCode(), response.body());
    var body = JSON.readTree(response.body());
    assertEquals(
        java.util.Set.of("openid", "api:read"),
        java.util.Set.of(body.path("scope").asText().split(" ")));
    assertTrue(body.path("expires_in").asLong() <= 60);
    var jwt =
        com.nimbusds.jwt.SignedJWT.parse(body.path("access_token").asText()).getJWTClaimsSet();
    assertEquals(
        java.util.Set.of("openid", "api:read"),
        java.util.Set.copyOf(jwt.getStringListClaim("scope")));
    assertTrue(jwt.getExpirationTime().getTime() - jwt.getIssueTime().getTime() <= 60000);
    var identity =
        com.nimbusds.jwt.SignedJWT.parse(body.path("id_token").asText()).getJWTClaimsSet();
    assertNull(identity.getClaim("email"));
    assertNull(identity.getClaim("name"));
    assertNotNull(identity.getClaim("nonce"));
    var saved =
        authorizations.findByToken(
            body.path("access_token").asText(),
            org.springframework.security.oauth2.server.authorization.OAuth2TokenType.ACCESS_TOKEN);
    assertEquals(java.util.Set.of("openid", "api:read"), saved.getAuthorizedScopes());
    assertEquals(saved.getAuthorizedScopes(), saved.getAccessToken().getToken().getScopes());
    var userinfo = userinfo(body.path("access_token").asText());
    assertEquals(200, userinfo.statusCode());
    assertEquals(1, JSON.readTree(userinfo.body()).size());
    issuancePolicy(flow, "ALLOW_LIMITED", List.of("api:read"), 60, List.of(), true);
    Flow next = flow(false, true);
    browser = browser();
    code = approveScopes(browser, next, pending(browser, next), List.of("openid", "api:read"));
    issuancePolicy(next, "ALLOW_LIMITED", List.of("api:read"), 60, List.of(), true);
    assertError(exchange(next, code, next.verifier(), CALLBACK), "invalid_grant");
  }

  @Test
  void refreshReevaluatesPolicyWithoutRestoringPreviouslyRemovedScopes() throws Exception {
    Flow flow = flow(true, true);
    HttpClient browser = browser();
    String code =
        approveScopes(
            browser,
            flow,
            pending(browser, flow),
            List.of("openid", "profile", "email", "api:read"));
    Pair initial = pair(exchange(flow, code, flow.verifier(), CALLBACK));
    issuancePolicy(flow, "ALLOW_LIMITED", List.of("openid", "api:read"), 45, List.of(), true);
    var limited = refresh(flow, initial.refresh(), null);
    assertEquals(200, limited.statusCode(), limited.body());
    var body = JSON.readTree(limited.body());
    assertTrue(body.path("expires_in").asLong() <= 45);
    assertEquals(
        java.util.Set.of("openid", "api:read"),
        java.util.Set.of(body.path("scope").asText().split(" ")));
    issuancePolicy(flow, "ALLOW", List.of(), null, List.of(), true);
    assertError(refresh(flow, body.path("refresh_token").asText(), "email"), "invalid_scope");
    var again = refresh(flow, body.path("refresh_token").asText(), null);
    assertEquals(200, again.statusCode());
    assertEquals(
        java.util.Set.of("openid", "api:read"),
        java.util.Set.of(JSON.readTree(again.body()).path("scope").asText().split(" ")));
  }

  @Test
  void tokenPolicyRequiresMfaFromTheOriginalSessionAndRechecksItOnRefresh() throws Exception {
    Flow flow = flow(true);
    HttpClient browser = browser();
    String code = approve(browser, flow, pending(browser, flow));
    issuancePolicy(flow, "ALLOW_WITH_MFA", List.of(), null, List.of(), true);
    assertError(exchange(flow, code, flow.verifier(), CALLBACK), "invalid_grant");
    HttpClient other = mfaLogin(flow);
    String seed = mfaSeed(mfaPost(other, "/mfa/enroll", Map.of("password", PASSWORD)));
    var recovery = mfaRecovery(mfaPost(other, "/mfa/confirm", Map.of("code", mfaCode(seed))));
    assertError(exchange(flow, code, flow.verifier(), CALLBACK), "invalid_grant");
    assertEquals(
        200,
        mfaPost(browser, "/mfa/verify", Map.of("method", "RECOVERY", "code", recovery.getFirst()))
            .statusCode());
    Pair issued = pair(exchange(flow, code, flow.verifier(), CALLBACK));
    java.time.Instant now = java.time.Instant.now();
    org.mockito.Mockito.doReturn(now.plusSeconds(301)).when(riskClock).instant();
    assertError(refresh(flow, issued.refresh(), null), "invalid_grant");
    assertEquals(
        200,
        mfaPost(browser, "/mfa/verify", Map.of("method", "RECOVERY", "code", recovery.get(1)))
            .statusCode());
    assertEquals(200, refresh(flow, issued.refresh(), null).statusCode());
  }

  @Test
  void tokenPolicyUsesTheServerBrowserContextForLiveNetworkEvidence() throws Exception {
    Flow flow = flow();
    HttpClient browser = browser();
    String code = approve(browser, flow, pending(browser, flow));
    issuancePolicy(
        flow,
        "ALLOW",
        List.of(),
        null,
        List.of(
            Map.of("attribute", "NETWORK_TRUST", "operator", "EQ", "values", List.of("TRUSTED"))),
        true);
    assertError(exchange(flow, code, flow.verifier(), CALLBACK), "invalid_grant");
    // Other tests may already have an allowlisted loopback network; isolate the classification.
    var networks =
        jdbc.queryForList(
            "SELECT id,enabled,version FROM iam_policy.trusted_networks WHERE network='127.0.0.1/32'::cidr");
    if (networks.isEmpty()) {
      assertEquals(
          201,
          admin("POST", "/api/admin/risk/networks", Map.of("cidr", "127.0.0.1/32")).statusCode());
    } else {
      var network = networks.getFirst();
      assertEquals(
          200,
          admin(
                  "PUT",
                  "/api/admin/risk/networks/" + network.get("id"),
                  Map.of("enabled", true, "version", network.get("version")))
              .statusCode());
    }
    assertEquals(200, exchange(flow, code, flow.verifier(), CALLBACK).statusCode());
    jdbc.update(
        "UPDATE iam_policy.trusted_networks SET enabled=false WHERE network='127.0.0.1/32'::cidr");
  }

  @Test
  void policyAuditFailureRollsBackCodeAndRefreshIssuance() throws Exception {
    Flow flow = flow(true);
    HttpClient browser = browser();
    String code = approve(browser, flow, pending(browser, flow));
    org.mockito.Mockito.doThrow(new IllegalStateException("synthetic policy audit failure"))
        .when(mfaAudit)
        .record(
            org.mockito.ArgumentMatchers.argThat(
                event -> event.type().value().equals("oauth.policy.applied")));
    var failed = exchange(flow, code, flow.verifier(), CALLBACK);
    assertEquals(503, failed.statusCode());
    assertEquals("temporarily_unavailable", JSON.readTree(failed.body()).path("error").asText());
    org.mockito.Mockito.reset(mfaAudit);
    Pair issued = pair(exchange(flow, code, flow.verifier(), CALLBACK));
    org.mockito.Mockito.doThrow(new IllegalStateException("synthetic policy audit failure"))
        .when(mfaAudit)
        .record(
            org.mockito.ArgumentMatchers.argThat(
                event -> event.type().value().equals("oauth.policy.applied")));
    assertEquals(503, refresh(flow, issued.refresh(), null).statusCode());
    org.mockito.Mockito.reset(mfaAudit);
    assertEquals(200, refresh(flow, issued.refresh(), null).statusCode());
  }

  @Test
  void policyCollectorFailureCannotFallBackToUnrestrictedTokens() throws Exception {
    Flow flow = flow();
    HttpClient browser = browser();
    String code = approve(browser, flow, pending(browser, flow));
    org.mockito.Mockito.doThrow(new IllegalStateException("synthetic collector failure"))
        .when(enforcementPolicies)
        .decide(
            org.mockito.ArgumentMatchers.eq(flow.userId()),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anySet(),
            org.mockito.ArgumentMatchers.any());
    assertEquals(503, exchange(flow, code, flow.verifier(), CALLBACK).statusCode());
    org.mockito.Mockito.reset(enforcementPolicies);
    assertEquals(200, exchange(flow, code, flow.verifier(), CALLBACK).statusCode());
  }

  private void issuancePolicy(
      Flow flow,
      String effect,
      List<String> scopes,
      Integer ttl,
      List<Map<String, Object>> conditions,
      boolean enabled)
      throws Exception {
    UUID id =
        jdbc.queryForObject(
            "SELECT id FROM iam_policy.policies WHERE name=?",
            UUID.class,
            "issuance-" + flow.clientId());
    var previous = JSON.readTree(admin("GET", "/api/admin/policies/" + id, Map.of()).body());
    var rule = new HashMap<String, Object>();
    rule.put("schemaVersion", conditions.isEmpty() ? 1 : 2);
    rule.put("resource", "oauth:" + flow.clientId());
    rule.put("action", "issue");
    rule.put("effect", effect);
    rule.put("rolesAnyOf", List.of());
    rule.put("allowedScopes", scopes);
    if (ttl != null) {
      rule.put("maxAccessTokenTtlSeconds", ttl);
    }
    if (!conditions.isEmpty()) {
      rule.put("conditions", conditions);
    }
    var result =
        admin(
            "PUT",
            "/api/admin/policies/" + id,
            Map.of(
                "name",
                "issuance-" + flow.clientId(),
                "priority",
                100,
                "enabled",
                enabled,
                "version",
                previous.path("version").asLong(),
                "document",
                rule));
    assertEquals(200, result.statusCode(), result.body());
  }

  @Autowired com.securegate.iam.identity.application.port.MfaProofs mfaProofs;

  @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
  com.securegate.iam.audit.application.port.AuditTrail mfaAudit;

  @Test
  void mfaEnrollmentIsCsrfProtectedEncryptedAndReturnsRecoveryCodesOnlyOnce() throws Exception {
    Flow flow = flow();
    HttpClient browser = mfaLogin(flow);
    org.mockito.Mockito.doReturn(java.time.Instant.now()).when(riskClock).instant();
    assertEquals(403, post(browser, "/mfa/enroll", Map.of("password", PASSWORD)).statusCode());
    assertEquals(
        400,
        mfaPost(browser, "/mfa/enroll", Map.of("password", "incorrect-password")).statusCode());
    var enrollment = mfaPost(browser, "/mfa/enroll", Map.of("password", PASSWORD));
    assertEquals(200, enrollment.statusCode());
    assertEquals("no-store", enrollment.headers().firstValue("Cache-Control").orElseThrow());
    assertTrue(
        enrollment
            .headers()
            .firstValue("Content-Security-Policy")
            .orElseThrow()
            .contains("default-src 'none'"));
    String seed = mfaSeed(enrollment);
    String ciphertext =
        jdbc.queryForObject(
            "SELECT ciphertext FROM iam_identity.mfa_factors WHERE user_id=?",
            String.class,
            flow.userId());
    assertTrue(ciphertext.startsWith("vault:v1:"));
    assertFalse(ciphertext.contains(seed));
    assertFalse(get(browser, "/mfa").body().contains(seed));
    var activated = mfaPost(browser, "/mfa/confirm", Map.of("code", mfaCode(seed)));
    assertEquals(200, activated.statusCode());
    var codes = mfaRecovery(activated);
    assertEquals(10, codes.size());
    assertEquals(10, codes.stream().distinct().count());
    assertEquals(
        10,
        jdbc.queryForObject(
            "SELECT count(*) FROM iam_identity.mfa_recovery_codes c JOIN iam_identity.mfa_factors f ON f.id=c.factor_id WHERE f.user_id=? AND c.code_hash ~ '^[0-9a-f]{64}$'",
            Integer.class,
            flow.userId()));
    var page = get(browser, "/mfa");
    assertTrue(page.body().contains("Step-up"));
    for (String code : codes) {
      assertFalse(page.body().contains(code));
    }
    assertEquals(400, mfaPost(browser, "/mfa/confirm", Map.of("code", mfaCode(seed))).statusCode());
    assertEquals(400, mfaPost(browser, "/mfa/enroll", Map.of("password", PASSWORD)).statusCode());
    assertEquals(
        400,
        mfaPost(browser, "/mfa/verify", Map.of("method", "TOTP", "code", mfaCode(seed)))
            .statusCode());
    String details =
        jdbc.queryForObject(
            "SELECT string_agg(details::text,',') FROM iam_audit.security_events WHERE resource=?",
            String.class,
            "identity/mfa/" + flow.userId());
    assertFalse(details.contains(seed));
    for (String code : codes) {
      assertFalse(details.contains(code));
    }
    assertEquals(302, get(browser(), "/mfa").statusCode());
    assertEquals(401, admin("GET", "/mfa", Map.of()).statusCode());
  }

  @Test
  void mfaConfirmationBindsPendingEnrollmentToSessionAndExpiresIt() throws Exception {
    Flow flow = flow();
    HttpClient owner = mfaLogin(flow);
    HttpClient other = mfaLogin(flow);
    // PostgreSQL persists instants at microsecond precision; keep the exact boundary deterministic.
    java.time.Instant now =
        java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    org.mockito.Mockito.doReturn(now).when(riskClock).instant();
    String seed = mfaSeed(mfaPost(owner, "/mfa/enroll", Map.of("password", PASSWORD)));
    assertEquals(400, mfaPost(other, "/mfa/confirm", Map.of("code", mfaCode(seed))).statusCode());
    org.mockito.Mockito.doReturn(now.plusSeconds(300)).when(riskClock).instant();
    assertEquals(400, mfaPost(owner, "/mfa/confirm", Map.of("code", mfaCode(seed))).statusCode());
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM iam_session.mfa_proofs WHERE user_id=?",
            Integer.class,
            flow.userId()));
    assertEquals(400, mfaPost(owner, "/mfa/enroll", Map.of("password", PASSWORD)).statusCode());
  }

  @Test
  void mfaAttemptsAreSharedAcrossSessionsAndRemainCommittedOnRejection() throws Exception {
    Flow flow = flow();
    HttpClient first = mfaLogin(flow);
    HttpClient second = mfaLogin(flow);
    java.time.Instant now = java.time.Instant.now();
    org.mockito.Mockito.doReturn(now).when(riskClock).instant();
    for (int attempt = 0; attempt < 5; attempt++) {
      assertEquals(
          attempt == 4 ? 429 : 400,
          mfaPost(
                  attempt % 2 == 0 ? first : second,
                  "/mfa/enroll",
                  Map.of("password", "incorrect-password"))
              .statusCode());
    }
    assertEquals(
        5,
        jdbc.queryForObject(
            "SELECT failures FROM iam_identity.mfa_factors WHERE user_id=?",
            Integer.class,
            flow.userId()));
    assertEquals(429, mfaPost(second, "/mfa/enroll", Map.of("password", PASSWORD)).statusCode());
    org.mockito.Mockito.doReturn(now.plusSeconds(301)).when(riskClock).instant();
    assertEquals(
        400,
        mfaPost(first, "/mfa/verify", Map.of("method", "TOTP", "code", "000000")).statusCode());
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT failures FROM iam_identity.mfa_factors WHERE user_id=?",
            Integer.class,
            flow.userId()));
  }

  @Test
  void mfaRecoveryAndTotpAreConsumedOnceUnderConcurrency() throws Exception {
    Flow flow = flow();
    HttpClient first = mfaLogin(flow);
    HttpClient second = mfaLogin(flow);
    java.time.Instant now = java.time.Instant.now();
    org.mockito.Mockito.doReturn(now).when(riskClock).instant();
    String seed = mfaSeed(mfaPost(first, "/mfa/enroll", Map.of("password", PASSWORD)));
    List<String> codes = mfaRecovery(mfaPost(first, "/mfa/confirm", Map.of("code", mfaCode(seed))));
    String csrf1 = hidden(get(first, "/mfa").body(), "_csrf");
    String csrf2 = hidden(get(second, "/mfa").body(), "_csrf");
    try (var callers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var one =
          callers.submit(
              () ->
                  post(
                          first,
                          "/mfa/verify",
                          Map.of("method", "RECOVERY", "code", codes.getFirst(), "_csrf", csrf1))
                      .statusCode());
      var two =
          callers.submit(
              () ->
                  post(
                          second,
                          "/mfa/verify",
                          Map.of("method", "RECOVERY", "code", codes.getFirst(), "_csrf", csrf2))
                      .statusCode());
      assertEquals(
          List.of(200, 400), java.util.stream.Stream.of(one.get(), two.get()).sorted().toList());
    }
    assertEquals(
        9,
        jdbc.queryForObject(
            "SELECT count(*) FROM iam_identity.mfa_recovery_codes c JOIN iam_identity.mfa_factors f ON f.id=c.factor_id WHERE f.user_id=?",
            Integer.class,
            flow.userId()));
    org.mockito.Mockito.doReturn(now.plusSeconds(30)).when(riskClock).instant();
    String code = mfaCode(seed);
    try (var callers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var one =
          callers.submit(
              () ->
                  post(first, "/mfa/verify", Map.of("method", "TOTP", "code", code, "_csrf", csrf1))
                      .statusCode());
      var two =
          callers.submit(
              () ->
                  post(
                          second,
                          "/mfa/verify",
                          Map.of("method", "TOTP", "code", code, "_csrf", csrf2))
                      .statusCode());
      assertEquals(
          List.of(200, 400), java.util.stream.Stream.of(one.get(), two.get()).sorted().toList());
    }
  }

  @Test
  void mfaAuditFailureRollsBackActivationRecoveryConsumptionAndProof() throws Exception {
    Flow flow = flow();
    HttpClient browser = mfaLogin(flow);
    org.mockito.Mockito.doReturn(java.time.Instant.now()).when(riskClock).instant();
    String seed = mfaSeed(mfaPost(browser, "/mfa/enroll", Map.of("password", PASSWORD)));
    org.mockito.Mockito.doThrow(new IllegalStateException("synthetic audit failure"))
        .when(mfaAudit)
        .record(
            org.mockito.ArgumentMatchers.argThat(
                event -> event.type().value().equals("identity.mfa.activated")));
    assertEquals(500, mfaPost(browser, "/mfa/confirm", Map.of("code", mfaCode(seed))).statusCode());
    assertEquals(
        "PENDING",
        jdbc.queryForObject(
            "SELECT state FROM iam_identity.mfa_factors WHERE user_id=?",
            String.class,
            flow.userId()));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM iam_session.mfa_proofs WHERE user_id=?",
            Integer.class,
            flow.userId()));
    org.mockito.Mockito.reset(mfaAudit);
    var codes = mfaRecovery(mfaPost(browser, "/mfa/confirm", Map.of("code", mfaCode(seed))));
    org.mockito.Mockito.doThrow(new IllegalStateException("synthetic audit failure"))
        .when(mfaAudit)
        .record(
            org.mockito.ArgumentMatchers.argThat(
                event -> event.type().value().equals("identity.mfa.recoveryused")));
    assertEquals(
        500,
        mfaPost(browser, "/mfa/verify", Map.of("method", "RECOVERY", "code", codes.getFirst()))
            .statusCode());
    assertEquals(
        10,
        jdbc.queryForObject(
            "SELECT count(*) FROM iam_identity.mfa_recovery_codes c JOIN iam_identity.mfa_factors f ON f.id=c.factor_id WHERE f.user_id=?",
            Integer.class,
            flow.userId()));
    org.mockito.Mockito.reset(mfaAudit);
    assertEquals(
        200,
        mfaPost(browser, "/mfa/verify", Map.of("method", "RECOVERY", "code", codes.getFirst()))
            .statusCode());
  }

  @Test
  void mfaProofExpiresAndIsInvalidatedByReauthenticationLogoutAndIdentityChanges()
      throws Exception {
    Flow flow = flow();
    HttpClient browser = mfaLogin(flow);
    java.time.Instant now = java.time.Instant.now();
    org.mockito.Mockito.doReturn(now).when(riskClock).instant();
    String seed = mfaSeed(mfaPost(browser, "/mfa/enroll", Map.of("password", PASSWORD)));
    List<String> codes =
        mfaRecovery(mfaPost(browser, "/mfa/confirm", Map.of("code", mfaCode(seed))));
    UUID sid =
        jdbc.queryForObject(
            "SELECT session_id FROM iam_session.mfa_proofs WHERE user_id=?",
            UUID.class,
            flow.userId());
    assertTrue(mfaProofs.current(flow.userId(), 0, sid).isPresent());
    assertTrue(mfaProofs.current(flow.userId(), 0, UUID.randomUUID()).isEmpty());
    assertTrue(mfaProofs.current(UUID.randomUUID(), 0, sid).isEmpty());
    org.mockito.Mockito.doReturn(now.plusSeconds(300)).when(riskClock).instant();
    assertTrue(mfaProofs.current(flow.userId(), 0, sid).isEmpty());
    org.mockito.Mockito.doReturn(now).when(riskClock).instant();
    var login = get(browser, "/login");
    assertEquals(
        302,
        post(
                browser,
                "/login",
                Map.of(
                    "username",
                    flow.email(),
                    "password",
                    PASSWORD,
                    "_csrf",
                    hidden(login.body(), "_csrf")))
            .statusCode());
    assertTrue(mfaProofs.current(flow.userId(), 0, sid).isEmpty());
    assertEquals(
        200,
        mfaPost(browser, "/mfa/verify", Map.of("method", "RECOVERY", "code", codes.getFirst()))
            .statusCode());
    sid =
        jdbc.queryForObject(
            "SELECT session_id FROM iam_session.mfa_proofs WHERE user_id=?",
            UUID.class,
            flow.userId());
    assertEquals(
        302,
        post(browser, "/logout", Map.of("_csrf", hidden(get(browser, "/login").body(), "_csrf")))
            .statusCode());
    assertTrue(mfaProofs.current(flow.userId(), 0, sid).isEmpty());
    browser = mfaLogin(flow);
    assertEquals(
        200,
        mfaPost(browser, "/mfa/verify", Map.of("method", "RECOVERY", "code", codes.get(1)))
            .statusCode());
    sid =
        jdbc.queryForObject(
            "SELECT session_id FROM iam_session.mfa_proofs WHERE user_id=?",
            UUID.class,
            flow.userId());
    jdbc.update("UPDATE iam_identity.users SET version=version+1 WHERE id=?", flow.userId());
    assertTrue(mfaProofs.current(flow.userId(), 0, sid).isEmpty());
    assertEquals(302, get(browser, "/mfa").statusCode());
  }

  @Test
  void mfaVaultFailureReturnsSanitized503WithoutConsumingConfirmation() throws Exception {
    Flow flow = flow();
    HttpClient browser = mfaLogin(flow);
    org.mockito.Mockito.doReturn(java.time.Instant.now()).when(riskClock).instant();
    String seed = mfaSeed(mfaPost(browser, "/mfa/enroll", Map.of("password", PASSWORD)));
    jdbc.update(
        "UPDATE iam_identity.mfa_factors SET ciphertext='vault:v999:AAAA' WHERE user_id=?",
        flow.userId());
    var denied = mfaPost(browser, "/mfa/confirm", Map.of("code", mfaCode(seed)));
    assertEquals(503, denied.statusCode());
    assertFalse(denied.body().contains(seed));
    assertFalse(denied.body().contains("vault:v"));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT failures FROM iam_identity.mfa_factors WHERE user_id=?",
            Integer.class,
            flow.userId()));
    assertEquals(
        -1L,
        jdbc.queryForObject(
            "SELECT last_counter FROM iam_identity.mfa_factors WHERE user_id=?",
            Long.class,
            flow.userId()));
  }

  @Test
  void closingMfaSessionCannotBeUndoneByAnInFlightVerification() throws Exception {
    Flow flow = flow();
    HttpClient browser = mfaLogin(flow);
    java.time.Instant now = java.time.Instant.now();
    org.mockito.Mockito.doReturn(now).when(riskClock).instant();
    String seed = mfaSeed(mfaPost(browser, "/mfa/enroll", Map.of("password", PASSWORD)));
    List<String> codes =
        mfaRecovery(mfaPost(browser, "/mfa/confirm", Map.of("code", mfaCode(seed))));
    UUID sid =
        jdbc.queryForObject(
            "SELECT session_id FROM iam_session.mfa_proofs WHERE user_id=?",
            UUID.class,
            flow.userId());
    var session =
        new com.securegate.iam.identity.application.MfaService.Session(flow.userId(), 0, sid, now);
    var context =
        new com.securegate.iam.shared.application.port.OperationContext(
            flow.userId().toString(), "mfa-close-race");
    var verified = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    org.mockito.Mockito.doAnswer(
            call -> {
              verified.countDown();
              assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS));
              return call.callRealMethod();
            })
        .when(mfaAudit)
        .record(
            org.mockito.ArgumentMatchers.argThat(
                event -> event.type().value().equals("identity.mfa.recoveryused")));
    try (var callers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var verification =
          callers.submit(() -> mfa.verify(session, "RECOVERY", codes.getFirst(), context));
      try {
        assertTrue(verified.await(5, java.util.concurrent.TimeUnit.SECONDS));
        var closing = callers.submit(() -> mfaProofs.revokeSession(sid));
        release.countDown();
        assertEquals(
            "SUCCESS", verification.get(5, java.util.concurrent.TimeUnit.SECONDS).outcome());
        closing.get(5, java.util.concurrent.TimeUnit.SECONDS);
      } finally {
        release.countDown();
      }
    }
    assertTrue(mfaProofs.current(flow.userId(), 0, sid).isEmpty());
    assertThrows(
        com.securegate.iam.identity.application.MfaService.StaleSession.class,
        () -> mfa.verify(session, "RECOVERY", codes.get(1), context));
    assertEquals(
        9,
        jdbc.queryForObject(
            "SELECT count(*) FROM iam_identity.mfa_recovery_codes c JOIN iam_identity.mfa_factors f ON f.id=c.factor_id WHERE f.user_id=?",
            Integer.class,
            flow.userId()));
  }

  private HttpClient mfaLogin(Flow flow) throws Exception {
    HttpClient browser = browser();
    var page = get(browser, "/login");
    assertEquals(
        302,
        post(
                browser,
                "/login",
                Map.of(
                    "username",
                    flow.email(),
                    "password",
                    PASSWORD,
                    "_csrf",
                    hidden(page.body(), "_csrf")))
            .statusCode());
    assertEquals(200, get(browser, "/mfa").statusCode());
    return browser;
  }

  private HttpResponse<String> mfaPost(HttpClient browser, String path, Map<String, String> values)
      throws Exception {
    var body = new HashMap<>(values);
    body.put("_csrf", hidden(get(browser, "/mfa").body(), "_csrf"));
    return post(browser, path, body);
  }

  private static String mfaSeed(HttpResponse<String> response) {
    assertEquals(200, response.statusCode());
    var matcher = Pattern.compile("id=\"totp-secret\">([A-Z2-7]{32})").matcher(response.body());
    assertTrue(matcher.find());
    return matcher.group(1);
  }

  private static List<String> mfaRecovery(HttpResponse<String> response) {
    assertEquals(200, response.statusCode());
    return Pattern.compile("class=\"recovery-code\">([A-Za-z0-9_-]{22})")
        .matcher(response.body())
        .results()
        .map(match -> match.group(1))
        .toList();
  }

  private String mfaCode(String seed) throws Exception {
    String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    StringBuilder binary = new StringBuilder();
    for (char letter : seed.toCharArray()) {
      binary.append(
          String.format("%5s", Integer.toBinaryString(alphabet.indexOf(letter))).replace(' ', '0'));
    }
    byte[] key = new byte[20];
    for (int i = 0; i < 20; i++) {
      key[i] = (byte) Integer.parseInt(binary.substring(i * 8, i * 8 + 8), 2);
    }
    var hmac = javax.crypto.Mac.getInstance("HmacSHA1");
    hmac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA1"));
    byte[] digest =
        hmac.doFinal(
            java.nio.ByteBuffer.allocate(8)
                .putLong(riskClock.instant().getEpochSecond() / 30)
                .array());
    int offset = digest[19] & 15;
    return String.format(
        java.util.Locale.ROOT,
        "%06d",
        (java.nio.ByteBuffer.wrap(digest, offset, 4).getInt() & 0x7fffffff) % 1000000);
  }

  @Autowired
  org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService
      authorizations;

  @LocalServerPort int port;

  private JsonNode riskContext(Flow flow) throws Exception {
    var response = admin("GET", "/api/admin/risk/users/" + flow.userId() + "/contexts", Map.of());
    assertEquals(200, response.statusCode(), response.body());
    assertFalse(response.body().contains("127.0.0.1"));
    var contexts = JSON.readTree(response.body());
    assertEquals(1, contexts.size());
    return contexts.get(0);
  }

  @Test
  void reauthenticationClosesPreviousRiskContextBeforeIssuingANewOne() throws Exception {
    Flow flow = flow();
    var browser = browser();
    pending(browser, flow);
    var previous = riskContext(flow);
    var page = get(browser, "/login");
    var relogin =
        post(
            browser,
            "/login",
            Map.of(
                "username",
                flow.email(),
                "password",
                PASSWORD,
                "_csrf",
                hidden(page.body(), "_csrf")));
    assertEquals(302, relogin.statusCode());
    var current = riskContext(flow);
    assertNotEquals(previous.path("id").asText(), current.path("id").asText());
    assertEquals(previous.path("deviceId").asText(), current.path("deviceId").asText());
    assertNotNull(
        jdbc.queryForObject(
            "SELECT closed_at FROM iam_policy.browser_contexts WHERE id=?",
            java.sql.Timestamp.class,
            UUID.fromString(previous.path("id").asText())));
  }

  private JsonNode evaluateRisk(Flow flow, String resource, String contextId) throws Exception {
    var body =
        new HashMap<String, Object>(
            Map.of(
                "userId",
                flow.userId(),
                "resource",
                resource,
                "action",
                "read",
                "requestedScopes",
                List.of("api:read")));
    if (contextId != null) {
      body.put("contextId", contextId);
    }
    var response = admin("POST", "/api/admin/policies/evaluate", body);
    assertEquals(200, response.statusCode(), response.body());
    assertFalse(JSON.readTree(response.body()).path("simulation").asBoolean());
    return JSON.readTree(response.body()).path("risk");
  }

  @Test
  void collectsAuthenticatedBrowserContextIgnoresForwardedHeadersAndClosesItOnLogout()
      throws Exception {
    var now = java.time.Instant.now().plusSeconds(600);
    org.mockito.Mockito.doReturn(now).when(riskClock).instant();
    Flow flow = flow();
    var browser = browser();
    pending(browser, flow);
    var context = riskContext(flow);
    String contextId = context.path("id").asText();
    String deviceId = context.path("deviceId").asText();
    var manager = (CookieManager) browser.cookieHandler().orElseThrow();
    var cookie =
        manager.getCookieStore().getCookies().stream()
            .filter(value -> value.getName().equals("sg_device"))
            .findFirst()
            .orElseThrow();
    assertTrue(cookie.isHttpOnly());
    assertEquals(deviceId, cookie.getValue());
    assertEquals(
        201,
        admin(
                "POST",
                "/api/admin/risk/resources",
                Map.of(
                    "resource",
                    "browser-" + flow.suffix().toLowerCase(java.util.Locale.ROOT),
                    "sensitivity",
                    "PUBLIC"))
            .statusCode());
    String resource = "browser-" + flow.suffix().toLowerCase(java.util.Locale.ROOT);
    browser.send(
        HttpRequest.newBuilder(uri("/login"))
            .header("X-Forwarded-For", "203.0.113.9")
            .header("Forwarded", "for=203.0.113.9;proto=https")
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());
    assertEquals(
        "127.0.0.1",
        jdbc.queryForObject(
            "SELECT host(remote_address) FROM iam_policy.browser_contexts WHERE id=?",
            String.class,
            UUID.fromString(contextId)));
    var networks = admin("POST", "/api/admin/risk/networks", Map.of("cidr", "127.0.0.0/8"));
    assertEquals(201, networks.statusCode(), networks.body());
    String networkId = JSON.readTree(networks.body()).path("id").asText();
    try {
      assertEquals(25, evaluateRisk(flow, resource, contextId).path("score").asInt());
      var approved =
          admin(
              "PUT",
              "/api/admin/risk/users/" + flow.userId() + "/devices/" + deviceId,
              Map.of("trusted", true, "ttlSeconds", 600, "version", 0));
      assertEquals(200, approved.statusCode(), approved.body());
      assertEquals(0, evaluateRisk(flow, resource, contextId).path("score").asInt());
      assertEquals(45, evaluateRisk(flow, resource, null).path("score").asInt());
      Flow foreign = flow();
      assertEquals(45, evaluateRisk(foreign, resource, contextId).path("score").asInt());
      var page = get(browser, "/login");
      assertEquals(
          302,
          post(browser, "/logout", Map.of("_csrf", hidden(page.body(), "_csrf"))).statusCode());
      assertNotNull(
          jdbc.queryForObject(
              "SELECT closed_at FROM iam_policy.browser_contexts WHERE id=?",
              java.sql.Timestamp.class,
              UUID.fromString(contextId)));
      assertEquals(45, evaluateRisk(flow, resource, contextId).path("score").asInt());
    } finally {
      admin("PUT", "/api/admin/risk/networks/" + networkId, Map.of("enabled", false, "version", 0));
    }
  }

  @Test
  void countsActualKnownAccountFailuresAndDoesNotTransferBrowserApprovalBetweenUsers()
      throws Exception {
    var now = java.time.Instant.now().plusSeconds(600);
    org.mockito.Mockito.doReturn(now).when(riskClock).instant();
    Flow owner = flow();
    var browser = browser();
    var login = get(browser, "/login");
    for (int index = 0; index < 2; index++) {
      var failed =
          post(
              browser,
              "/login",
              Map.of(
                  "username",
                  owner.email(),
                  "password",
                  "wrong-password",
                  "_csrf",
                  hidden(login.body(), "_csrf")));
      assertEquals(302, failed.statusCode());
      assertTrue(location(failed).endsWith("/login?error"));
    }
    var unknown =
        post(
            browser,
            "/login",
            Map.of(
                "username",
                "unknown@example.test",
                "password",
                "wrong-password",
                "_csrf",
                hidden(login.body(), "_csrf")));
    assertEquals(302, unknown.statusCode());
    assertEquals(2, loginHistory.recentFailures(owner.userId()));
    pending(browser, owner);
    var first = riskContext(owner);
    var approved =
        admin(
            "PUT",
            "/api/admin/risk/users/"
                + owner.userId()
                + "/devices/"
                + first.path("deviceId").asText(),
            Map.of("trusted", true, "ttlSeconds", 600, "version", 0));
    assertEquals(200, approved.statusCode());
    var browser2 = browser();
    var manager = (CookieManager) browser2.cookieHandler().orElseThrow();
    var copied = new java.net.HttpCookie("sg_device", first.path("deviceId").asText());
    copied.setPath("/");
    manager.getCookieStore().add(uri("/"), copied);
    Flow other = flow();
    pending(browser2, other);
    var second = riskContext(other);
    assertNotEquals(first.path("deviceId").asText(), second.path("deviceId").asText());
    assertTrue(second.path("trustedUntil").isNull());
    var risk = evaluateRisk(owner, "unclassified", first.path("id").asText());
    assertTrue(risk.path("reasons").toString().contains("RECENT_LOGIN_FAILURES"));
    org.mockito.Mockito.doReturn(now.plusSeconds(301)).when(riskClock).instant();
    assertEquals(0, loginHistory.recentFailures(owner.userId()));
  }

  @Test
  void completesLoginConsentAndPkceAndPersistsOnlySafeIdentityMetadata() throws Exception {
    Flow flow = flow();
    UUID direct = UUID.randomUUID();
    UUID inherited = UUID.randomUUID();
    UUID group = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO iam_access.roles(id,name) VALUES (?,?),(?,?)",
        direct,
        "READER_" + flow.suffix(),
        inherited,
        "GROUP_" + flow.suffix());
    jdbc.update(
        "INSERT INTO iam_access.user_roles(user_id,role_id) VALUES (?,?)", flow.userId(), direct);
    jdbc.update(
        "INSERT INTO iam_access.groups(id,name) VALUES (?,?)", group, "TEAM_" + flow.suffix());
    jdbc.update(
        "INSERT INTO iam_access.group_members(group_id,user_id) VALUES (?,?)",
        group,
        flow.userId());
    jdbc.update(
        "INSERT INTO iam_access.group_roles(group_id,role_id) VALUES (?,?)", group, inherited);
    var browser = browser();
    var pending = pending(browser, flow);
    assertTrue(pending.body().contains("&lt;script&gt;"));
    assertFalse(pending.body().contains("<script>"));
    assertEquals("no-store", pending.headers().firstValue("Cache-Control").orElseThrow());
    assertEquals("no-referrer", pending.headers().firstValue("Referrer-Policy").orElseThrow());
    String code = approve(browser, flow, pending);
    var response = exchange(flow, code, flow.verifier(), CALLBACK);
    assertEquals(200, response.statusCode());
    var body = JSON.readTree(response.body());
    assertFalse(body.has("refresh_token"));
    assertFalse(body.has("id_token"));
    var decoder = NimbusJwtDecoder.withJwkSetUri(uri("/oauth2/jwks").toString()).build();
    decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
    var jwt = decoder.decode(body.path("access_token").asText());
    assertEquals(flow.userId().toString(), jwt.getSubject());
    assertEquals(List.of("browser-api"), jwt.getAudience());
    assertEquals(List.of("api:read"), jwt.getClaimAsStringList("scope"));
    assertEquals(
        List.of("GROUP_" + flow.suffix(), "READER_" + flow.suffix()),
        jwt.getClaimAsStringList("roles"));
    assertNotNull(UUID.fromString(jwt.getClaimAsString("sid")));
    assertEquals(300, Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt()).getSeconds());
    assertEquals(flow.clientId(), jwt.getClaimAsString("client_id"));
    String attributes =
        jdbc.queryForObject(
            "SELECT attributes FROM iam_authorization.oauth2_authorization WHERE access_token_value=?",
            String.class,
            jwt.getTokenValue());
    assertFalse(attributes.contains("argon2"));
    assertFalse(attributes.contains(PASSWORD));
    assertError(exchange(flow, code, flow.verifier(), CALLBACK), "invalid_grant");
  }

  @Test
  void protectsLoginAgainstCsrfAndRotatesTheSessionIdentifier() throws Exception {
    Flow flow = flow();
    CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    var browser = HttpClient.newBuilder().cookieHandler(cookies).build();
    var page = get(browser, "/login");
    assertEquals(200, page.statusCode());
    String before =
        cookies.getCookieStore().getCookies().stream()
            .filter(cookie -> cookie.getName().equals("JSESSIONID"))
            .findFirst()
            .orElseThrow()
            .getValue();
    String header = page.headers().allValues("Set-Cookie").toString();
    assertTrue(header.contains("HttpOnly"));
    assertTrue(header.contains("SameSite=Lax"));
    assertEquals(
        403,
        post(browser, "/login", Map.of("username", flow.email(), "password", PASSWORD))
            .statusCode());
    var result =
        post(
            browser,
            "/login",
            Map.of(
                "username",
                flow.email().toUpperCase(java.util.Locale.ROOT),
                "password",
                PASSWORD,
                "_csrf",
                hidden(page.body(), "_csrf")));
    assertEquals(302, result.statusCode());
    String after =
        cookies.getCookieStore().getCookies().stream()
            .filter(cookie -> cookie.getName().equals("JSESSIONID"))
            .findFirst()
            .orElseThrow()
            .getValue();
    assertNotEquals(before, after);
    assertEquals(403, post(browser, "/logout", Map.of()).statusCode());
    String csrf = hidden(get(browser, "/login").body(), "_csrf");
    assertEquals(302, post(browser, "/logout", Map.of("_csrf", csrf)).statusCode());
    assertTrue(location(get(browser, authorize(flow))).contains("/login"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"wrong-password", "missing", "LOCKED", "DISABLED"})
  void returnsTheSameLoginFailureForUnknownWrongAndInactiveAccounts(String mode) throws Exception {
    Flow flow = flow();
    if (mode.equals("LOCKED") || mode.equals("DISABLED")) {
      jdbc.update(
          "UPDATE iam_identity.users SET status=?,version=version+1 WHERE id=?",
          mode,
          flow.userId());
    }
    var browser = browser();
    var page = get(browser, "/login");
    var result =
        post(
            browser,
            "/login",
            Map.of(
                "username",
                mode.equals("missing") ? "missing@example.test" : flow.email(),
                "password",
                mode.equals("wrong-password") ? "Incorrect-password-123" : PASSWORD,
                "_csrf",
                hidden(page.body(), "_csrf")));
    assertEquals(302, result.statusCode());
    assertTrue(location(result).endsWith("/login?error"));
    var error = get(browser, "/login?error");
    assertTrue(error.body().contains("Não foi possível entrar. Confira suas credenciais."));
    assertFalse(error.body().contains(flow.email()));
    assertFalse(error.body().contains(PASSWORD));
  }

  @Test
  void requiresExactRedirectS256AndNonEmptyStateAndScopesBeforeLogin() throws Exception {
    Flow flow = flow();
    String valid = authorize(flow);
    for (String invalid :
        List.of(
            valid.replace("code_challenge_method=S256", "code_challenge_method=plain"),
            valid.replace("code_challenge_method=S256", "unused=x"),
            valid.replace("state=" + enc(flow.state()), "state="),
            valid.replace("scope=api%3Aread", "scope="),
            valid.replace(enc(CALLBACK), enc("http://127.0.0.1:18091/callback")),
            valid.replace(enc(CALLBACK), enc(CALLBACK + "/extra")),
            valid.replace(enc(CALLBACK), enc("https://attacker.example/callback")))) {
      var response = get(browser(), invalid);
      assertEquals(400, response.statusCode());
      assertTrue(response.headers().firstValue("Location").isEmpty());
    }
  }

  @Test
  void protectsConsentFromCsrfStateTamperingAndAnotherSessionOfTheSameUser() throws Exception {
    Flow flow = flow();
    var browser = browser();
    var page = pending(browser, flow);
    String state = hidden(page.body(), "state");
    assertEquals(
        403,
        post(
                browser,
                "/oauth2/authorize",
                Map.of("client_id", flow.clientId(), "state", state, "scope", "api:read"))
            .statusCode());
    assertEquals(
        403,
        post(
                browser,
                "/oauth2/authorize",
                Map.of(
                    "client_id",
                    flow.clientId(),
                    "state",
                    "forged-state",
                    "scope",
                    "api:read",
                    "_csrf",
                    hidden(page.body(), "_csrf")))
            .statusCode());
    assertEquals(
        404,
        get(browser, "/oauth2/consent?client_id=wrong-client&state=" + enc(state)).statusCode());
    var other = browser();
    var login = get(other, "/login");
    post(
        other,
        "/login",
        Map.of(
            "username",
            flow.email(),
            "password",
            PASSWORD,
            "_csrf",
            hidden(login.body(), "_csrf")));
    assertEquals(
        404,
        get(other, "/oauth2/consent?client_id=" + flow.clientId() + "&state=" + enc(state))
            .statusCode());
    assertEquals(
        403,
        post(
                other,
                "/oauth2/authorize",
                Map.of(
                    "client_id",
                    flow.clientId(),
                    "state",
                    state,
                    "scope",
                    "api:read",
                    "_csrf",
                    hidden(get(other, "/login").body(), "_csrf")))
            .statusCode());
    approve(browser, flow, page);
    assertEquals(
        403,
        post(
                browser,
                "/oauth2/authorize",
                Map.of(
                    "client_id",
                    flow.clientId(),
                    "state",
                    state,
                    "scope",
                    "api:read",
                    "_csrf",
                    hidden(page.body(), "_csrf")))
            .statusCode());
  }

  @Test
  void denialReturnsOriginalClientStateWithoutIssuingACode() throws Exception {
    Flow flow = flow();
    var browser = browser();
    var page = pending(browser, flow);
    var denied =
        post(
            browser,
            "/oauth2/authorize",
            Map.of(
                "client_id",
                flow.clientId(),
                "state",
                hidden(page.body(), "state"),
                "_csrf",
                hidden(page.body(), "_csrf")));
    assertEquals(302, denied.statusCode());
    var query = query(location(denied));
    assertEquals("access_denied", query.get("error"));
    assertEquals(flow.state(), query.get("state"));
    assertFalse(query.containsKey("code"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"verifier", "redirect", "expired"})
  void rejectsInvalidCodeExchange(String mode) throws Exception {
    Flow flow = flow();
    var browser = browser();
    String code = approve(browser, flow, pending(browser, flow));
    if (mode.equals("expired")) {
      jdbc.update(
          "UPDATE iam_authorization.oauth2_authorization SET authorization_code_issued_at=current_timestamp-interval '3 minutes', authorization_code_expires_at=current_timestamp-interval '1 minute' WHERE authorization_code_value=?",
          code);
    }
    assertError(
        exchange(
            flow,
            code,
            mode.equals("verifier") ? "x".repeat(64) : flow.verifier(),
            mode.equals("redirect") ? "http://127.0.0.1:18091/callback" : CALLBACK),
        "invalid_grant");
  }

  @ParameterizedTest
  @ValueSource(strings = {"LOCKED", "DISABLED", "PASSWORD"})
  void rejectsStaleSessionsAndCodesWhenIdentityChanges(String mode) throws Exception {
    Flow flow = flow();
    var browser = browser();
    String code = approve(browser, flow, pending(browser, flow));
    if (mode.equals("PASSWORD")) {
      assertEquals(
          204,
          admin(
                  "PUT",
                  "/api/admin/users/" + flow.userId() + "/password",
                  Map.of("password", "New-synthetic-password-123!", "version", 0))
              .statusCode());
    } else {
      jdbc.update(
          "UPDATE iam_identity.users SET status=?,version=version+1 WHERE id=?",
          mode,
          flow.userId());
    }
    assertError(exchange(flow, code, flow.verifier(), CALLBACK), "invalid_grant");
    var stale = get(browser, authorize(flow));
    assertEquals(302, stale.statusCode());
    assertTrue(location(stale).contains("/login"));
  }

  private Flow flow() throws Exception {
    return flow(false);
  }

  @Test
  void publishesOnlySupportedOidcAndOAuthMetadataWithFixedIssuer() throws Exception {
    for (String path :
        List.of("/.well-known/openid-configuration", "/.well-known/oauth-authorization-server")) {
      var response = get(HTTP, path);
      assertEquals(200, response.statusCode());
      var metadata = JSON.readTree(response.body());
      assertEquals(ISSUER, metadata.path("issuer").asText());
      assertEquals(ISSUER + "/oauth2/authorize", metadata.path("authorization_endpoint").asText());
      assertEquals(ISSUER + "/oauth2/token", metadata.path("token_endpoint").asText());
      assertEquals(ISSUER + "/oauth2/jwks", metadata.path("jwks_uri").asText());
      assertEquals(
          List.of("authorization_code", "client_credentials", "refresh_token"),
          JSON.convertValue(metadata.path("grant_types_supported"), List.class));
      assertEquals(
          List.of("client_secret_basic"),
          JSON.convertValue(metadata.path("token_endpoint_auth_methods_supported"), List.class));
      assertFalse(metadata.has("device_authorization_endpoint"));
      assertFalse(metadata.has("end_session_endpoint"));
      assertFalse(metadata.has("registration_endpoint"));
      assertFalse(metadata.has("dpop_signing_alg_values_supported"));
      assertFalse(metadata.has("tls_client_certificate_bound_access_tokens"));
      assertFalse(response.headers().firstValue("Set-Cookie").isPresent());
      if (path.contains("openid")) {
        assertEquals(ISSUER + "/userinfo", metadata.path("userinfo_endpoint").asText());
        assertEquals(
            List.of("RS256"),
            JSON.convertValue(metadata.path("id_token_signing_alg_values_supported"), List.class));
      }
    }
    assertEquals(403, get(HTTP, "/connect/register").statusCode());
    assertEquals(403, get(HTTP, "/connect/logout").statusCode());
  }

  @Test
  void completesOidcWithClientAudienceNonceAndScopedUserInfo() throws Exception {
    Flow flow = flow(true, true);
    var browser = browser();
    String code =
        approveScopes(
            browser, flow, pending(browser, flow), List.of("api:read", "profile", "email"));
    var issued = exchange(flow, code, flow.verifier(), CALLBACK);
    assertEquals(200, issued.statusCode(), issued.body());
    var tokens = JSON.readTree(issued.body());
    var decoder = oidcDecoder(flow.clientId());
    var id = decoder.decode(tokens.path("id_token").asText());
    assertEquals(List.of(flow.clientId()), id.getAudience());
    assertEquals(flow.userId().toString(), id.getSubject());
    assertEquals(flow.state(), id.getClaimAsString("nonce"));
    assertEquals(flow.clientId(), id.getClaimAsString("azp"));
    assertEquals("Synthetic User", id.getClaimAsString("name"));
    assertEquals(flow.email(), id.getClaimAsString("email"));
    assertFalse(id.hasClaim("email_verified"));
    assertFalse(id.hasClaim("roles"));
    assertFalse(id.hasClaim("scope"));
    assertFalse(id.hasClaim("client_id"));
    assertNotNull(id.getClaimAsInstant("auth_time"));
    assertEquals(300, Duration.between(id.getIssuedAt(), id.getExpiresAt()).toSeconds());
    var access =
        com.nimbusds.jwt.SignedJWT.parse(tokens.path("access_token").asText()).getJWTClaimsSet();
    assertEquals(List.of("browser-api"), access.getAudience());
    assertEquals(access.getStringClaim("sid"), id.getClaimAsString("sid"));
    assertThrows(
        org.springframework.security.oauth2.jwt.JwtValidationException.class,
        () -> oidcDecoder("other-client").decode(tokens.path("id_token").asText()));
    var info = userinfo(tokens.path("access_token").asText());
    assertEquals(200, info.statusCode(), info.body());
    assertEquals(3, JSON.readTree(info.body()).size());
    assertEquals(flow.userId().toString(), JSON.readTree(info.body()).path("sub").asText());
    assertEquals(flow.email(), JSON.readTree(info.body()).path("email").asText());
    assertTrue(info.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
    assertFalse(info.headers().firstValue("Set-Cookie").isPresent());
    assertEquals(401, userinfo(tokens.path("id_token").asText()).statusCode());
    var refreshed = refresh(flow, tokens.path("refresh_token").asText(), null);
    assertEquals(200, refreshed.statusCode(), refreshed.body());
    var renewed = JSON.readTree(refreshed.body());
    var renewedId = decoder.decode(renewed.path("id_token").asText());
    assertEquals(id.getSubject(), renewedId.getSubject());
    assertEquals(id.getClaimAsString("sid"), renewedId.getClaimAsString("sid"));
    assertEquals(id.getClaimAsInstant("auth_time"), renewedId.getClaimAsInstant("auth_time"));
    assertFalse(renewedId.hasClaim("nonce"));
    assertEquals(200, userinfo(renewed.path("access_token").asText()).statusCode());
    assertEquals(401, userinfo(tokens.path("access_token").asText()).statusCode());
    assertEquals(
        200,
        oauthPost("/oauth2/revoke", flow, Map.of("token", renewed.path("refresh_token").asText()))
            .statusCode());
    assertEquals(401, userinfo(renewed.path("access_token").asText()).statusCode());
  }

  @Test
  void disclosesOnlySubjectWhenProfileAndEmailAreNotApproved() throws Exception {
    Flow flow = flow(false, true);
    var browser = browser();
    String code = approve(browser, flow, pending(browser, flow));
    var tokens = JSON.readTree(exchange(flow, code, flow.verifier(), CALLBACK).body());
    var id = oidcDecoder(flow.clientId()).decode(tokens.path("id_token").asText());
    assertFalse(id.hasClaim("name"));
    assertFalse(id.hasClaim("email"));
    var info = userinfo(tokens.path("access_token").asText());
    assertEquals(200, info.statusCode());
    assertEquals(
        Map.of("sub", flow.userId().toString()),
        JSON.convertValue(JSON.readTree(info.body()), Map.class));
  }

  @ParameterizedTest
  @ValueSource(strings = {"LOCKED", "PASSWORD", "EXPIRED"})
  void rejectsUserInfoAfterIdentityChangesOrTokenExpires(String scenario) throws Exception {
    Flow flow = flow(false, true);
    var browser = browser();
    String code = approveScopes(browser, flow, pending(browser, flow), List.of("email"));
    var issued = exchange(flow, code, flow.verifier(), CALLBACK);
    assertEquals(200, issued.statusCode(), issued.body());
    String access = JSON.readTree(issued.body()).path("access_token").asText();
    assertEquals(200, userinfo(access).statusCode());
    if (scenario.equals("EXPIRED")) {
      jdbc.update(
          "UPDATE iam_authorization.oauth2_authorization SET access_token_issued_at=now()-interval '10 minutes',access_token_expires_at=now()-interval '5 minutes' WHERE access_token_value=?",
          access);
    } else if (scenario.equals("PASSWORD")) {
      var changed =
          admin(
              "PUT",
              "/api/admin/users/" + flow.userId() + "/password",
              Map.of("password", "Changed-synthetic-password-456!", "version", 0));
      assertEquals(204, changed.statusCode());
    } else {
      jdbc.update(
          "UPDATE iam_identity.users SET status='LOCKED',version=version+1 WHERE id=?",
          flow.userId());
    }
    var rejected = userinfo(access);
    assertEquals(401, rejected.statusCode(), rejected.body());
    assertFalse(rejected.body().contains(flow.email()));
  }

  @Test
  void rejectsMissingMalformedAndNonOidcUserInfoTokens() throws Exception {
    var missing = get(HTTP, "/userinfo");
    assertEquals(401, missing.statusCode());
    assertTrue(missing.headers().firstValue("WWW-Authenticate").orElseThrow().startsWith("Bearer"));
    assertFalse(missing.body().contains("invalid_client"));
    assertEquals(401, userinfo("malformed").statusCode());
    Flow flow = flow(false);
    var browser = browser();
    String code = approve(browser, flow, pending(browser, flow));
    var tokens = JSON.readTree(exchange(flow, code, flow.verifier(), CALLBACK).body());
    assertEquals(403, userinfo(tokens.path("access_token").asText()).statusCode());
    Flow oidc = flow(false, true);
    String request = authorize(oidc);
    for (String invalid :
        List.of(
            request.replace("&nonce=" + enc(oidc.state()), ""),
            request.replace("&nonce=" + enc(oidc.state()), "&nonce=short"),
            request.replace(enc(oidc.state()) + "&code_challenge", "short&code_challenge"),
            request + "&claims=%7B%7D",
            request + "&max_age=0",
            request + "&request=unsupported")) {
      assertEquals(400, get(HTTP, invalid).statusCode());
    }
    var denied =
        get(
            HTTP,
            authorize(flow).replace("scope=api%3Aread", "scope=openid") + "&nonce=" + flow.state());
    assertEquals(302, denied.statusCode());
    assertEquals("invalid_scope", query(location(denied)).get("error"));
    assertFalse(query(location(denied)).containsKey("code"));
  }

  @Test
  void narrowedRefreshDoesNotDisclosePreviousProfileOrEmailClaims() throws Exception {
    Flow flow = flow(true, true);
    var browser = browser();
    String code =
        approveScopes(
            browser, flow, pending(browser, flow), List.of("api:read", "profile", "email"));
    var issued = exchange(flow, code, flow.verifier(), CALLBACK);
    assertEquals(200, issued.statusCode());
    var tokens = JSON.readTree(issued.body());
    var renewedResponse = refresh(flow, tokens.path("refresh_token").asText(), "openid api:read");
    assertEquals(200, renewedResponse.statusCode(), renewedResponse.body());
    var renewed = JSON.readTree(renewedResponse.body());
    var id = oidcDecoder(flow.clientId()).decode(renewed.path("id_token").asText());
    assertFalse(id.hasClaim("name"));
    assertFalse(id.hasClaim("email"));
    var info = userinfo(renewed.path("access_token").asText());
    assertEquals(200, info.statusCode());
    assertEquals(1, JSON.readTree(info.body()).size());
    String original = tokens.path("access_token").asText();
    String[] segments = original.split("\\.");
    String tampered =
        segments[0]
            + "."
            + Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString("{\"sub\":\"attacker\"}".getBytes(StandardCharsets.UTF_8))
            + "."
            + segments[2];
    assertEquals(401, userinfo(tampered).statusCode());
    String unsignedHeader =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
    assertEquals(401, userinfo(unsignedHeader + "." + segments[1] + ".").statusCode());
  }

  private NimbusJwtDecoder oidcDecoder(String clientId) {
    var decoder = NimbusJwtDecoder.withJwkSetUri(uri("/oauth2/jwks").toString()).build();
    decoder.setJwtValidator(
        new org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer(ISSUER),
            new org.springframework.security.oauth2.jwt.JwtClaimValidator<List<String>>(
                "aud", audiences -> audiences != null && audiences.equals(List.of(clientId)))));
    return decoder;
  }

  private HttpResponse<String> userinfo(String token) throws Exception {
    return HTTP.send(
        HttpRequest.newBuilder(uri("/userinfo"))
            .header("Authorization", "Bearer " + token)
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void rotatesRefreshTokensAndPreservesIdentityAndTheAbsoluteFamilyDeadline() throws Exception {
    Flow flow = flow(true);
    Pair first = initialTokens(flow);
    assertActive(flow, first.access(), true);
    assertActive(flow, first.refresh(), true);
    String authorizationId = authorizationId(first.access());
    var deadline =
        jdbc.queryForObject(
            "SELECT expires_at FROM iam_authorization.refresh_families WHERE authorization_id=?",
            java.sql.Timestamp.class,
            authorizationId);
    UUID role = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO iam_access.roles(id,name) VALUES (?,?)", role, "REFRESH_" + flow.suffix());
    jdbc.update(
        "INSERT INTO iam_access.user_roles(user_id,role_id) VALUES (?,?)", flow.userId(), role);
    Pair second = pair(refresh(flow, first.refresh(), null));
    assertNotEquals(first.access(), second.access());
    assertNotEquals(first.refresh(), second.refresh());
    assertActive(flow, first.refresh(), false);
    assertActive(flow, second.refresh(), true);
    var decoder = NimbusJwtDecoder.withJwkSetUri(uri("/oauth2/jwks").toString()).build();
    decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
    var oldJwt = decoder.decode(first.access());
    var newJwt = decoder.decode(second.access());
    assertEquals(oldJwt.getSubject(), newJwt.getSubject());
    assertEquals(oldJwt.getClaimAsString("sid"), newJwt.getClaimAsString("sid"));
    assertEquals(List.of("REFRESH_" + flow.suffix()), newJwt.getClaimAsStringList("roles"));
    assertEquals(List.of("api:read"), newJwt.getClaimAsStringList("scope"));
    assertEquals(
        deadline,
        jdbc.queryForObject(
            "SELECT refresh_token_expires_at FROM iam_authorization.oauth2_authorization WHERE id=?",
            java.sql.Timestamp.class,
            authorizationId));
    String fingerprint =
        java.util.HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(first.refresh().getBytes(StandardCharsets.UTF_8)));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM iam_authorization.refresh_history WHERE token_hash=? AND consumed_at IS NOT NULL",
            Integer.class,
            fingerprint));
    for (String history :
        jdbc.queryForList(
            "SELECT to_jsonb(h)::text FROM iam_authorization.refresh_history h WHERE authorization_id=?",
            String.class,
            authorizationId)) {
      assertFalse(history.contains(first.refresh()));
      assertFalse(history.contains(second.refresh()));
    }
    assertEquals(
        second.refresh(),
        jdbc.queryForObject(
            "SELECT refresh_token_value FROM iam_authorization.oauth2_authorization WHERE id=?",
            String.class,
            authorizationId));
  }

  @Test
  void replayOfAnOldRefreshRevokesTheCurrentFamilyAndAuditsWithoutSecrets() throws Exception {
    Flow flow = flow(true);
    Pair first = initialTokens(flow);
    Pair second = pair(refresh(flow, first.refresh(), null));
    String id = authorizationId(second.access());
    assertError(refresh(flow, first.refresh(), null), "invalid_grant");
    assertActive(flow, second.access(), false);
    assertActive(flow, second.refresh(), false);
    assertError(refresh(flow, second.refresh(), null), "invalid_grant");
    assertError(refresh(flow, first.refresh(), null), "invalid_grant");
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM iam_audit.security_events WHERE event_type='oauth.refresh.reused' AND resource=?",
            Integer.class,
            id));
    String audit =
        jdbc.queryForObject(
            "SELECT details::text FROM iam_audit.security_events WHERE event_type='oauth.refresh.reused' AND resource=?",
            String.class,
            id);
    assertFalse(audit.contains(first.refresh()));
    assertFalse(audit.contains(second.refresh()));
    assertFalse(audit.contains(second.access()));
  }

  @Test
  void concurrentRefreshDetectsReplayAndRevokesTheWinningRotation() throws Exception {
    Flow flow = flow(true);
    Pair first = initialTokens(flow);
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var left = executor.submit(() -> refresh(flow, first.refresh(), null));
      var right = executor.submit(() -> refresh(flow, first.refresh(), null));
      var firstResponse = left.get();
      var secondResponse = right.get();
      assertEquals(
          java.util.Set.of(200, 400),
          java.util.Set.of(firstResponse.statusCode(), secondResponse.statusCode()));
      Pair winner = pair(firstResponse.statusCode() == 200 ? firstResponse : secondResponse);
      assertActive(flow, winner.access(), false);
      assertError(refresh(flow, winner.refresh(), null), "invalid_grant");
    }
  }

  @Test
  void badScopeOrForeignClientCannotConsumeOrRevokeTheOwnersRefresh() throws Exception {
    Flow owner = flow(true);
    Pair original = initialTokens(owner);
    Flow foreign = flow(false);
    assertError(refresh(owner, original.refresh(), "api:write"), "invalid_scope");
    assertError(refresh(foreign, original.refresh(), null), "invalid_grant");
    assertActive(foreign, original.access(), false);
    assertActive(foreign, original.refresh(), false);
    assertEquals(
        200,
        oauthPost("/oauth2/revoke", foreign, Map.of("token", original.refresh())).statusCode());
    assertActive(owner, original.refresh(), true);
    assertNotNull(pair(refresh(owner, original.refresh(), null)));
  }

  @ParameterizedTest
  @ValueSource(strings = {"refresh", "access", "historical"})
  void revocationIsIdempotentAndDisablesTheWholeRefreshFamily(String kind) throws Exception {
    Flow flow = flow(true);
    Pair first = initialTokens(flow);
    Pair current = pair(refresh(flow, first.refresh(), null));
    String selected =
        kind.equals("access")
            ? current.access()
            : kind.equals("historical") ? first.refresh() : current.refresh();
    assertEquals(200, oauthPost("/oauth2/revoke", flow, Map.of("token", selected)).statusCode());
    assertEquals(200, oauthPost("/oauth2/revoke", flow, Map.of("token", selected)).statusCode());
    assertEquals(
        200,
        oauthPost("/oauth2/revoke", flow, Map.of("token", "unknown-synthetic-token")).statusCode());
    assertActive(flow, current.access(), false);
    assertActive(flow, current.refresh(), false);
    assertError(refresh(flow, current.refresh(), null), "invalid_grant");
  }

  @ParameterizedTest
  @ValueSource(strings = {"LOCKED", "PASSWORD", "expired"})
  void rejectsRefreshWhenIdentityOrTheAbsoluteFamilyDeadlineChanges(String mode) throws Exception {
    Flow flow = flow(true);
    Pair first = initialTokens(flow);
    if (mode.equals("expired")) {
      jdbc.update(
          "UPDATE iam_authorization.refresh_families SET expires_at=current_timestamp-interval '1 second' WHERE authorization_id=?",
          authorizationId(first.access()));
    } else if (mode.equals("PASSWORD")) {
      assertEquals(
          204,
          admin(
                  "PUT",
                  "/api/admin/users/" + flow.userId() + "/password",
                  Map.of("password", "Changed-synthetic-password-123!", "version", 0))
              .statusCode());
    } else {
      jdbc.update(
          "UPDATE iam_identity.users SET status='LOCKED',version=version+1 WHERE id=?",
          flow.userId());
    }
    assertError(refresh(flow, first.refresh(), null), "invalid_grant");
    if (!mode.equals("expired")) {
      assertActive(flow, first.access(), false);
      assertActive(flow, first.refresh(), false);
    }
  }

  @Test
  void unavailableSigningDoesNotConsumeTheRefreshAndAllowsRetryAfterRecovery() throws Exception {
    Flow flow = flow(true);
    Pair first = initialTokens(flow);
    try {
      vault(
          "sys/policies/acl/securegate-iam-core",
          Map.of("policy", "path \"transit/keys/securegate-jwt\" { capabilities = [\"read\"] }"));
      var failed = refresh(flow, first.refresh(), null);
      assertEquals(503, failed.statusCode());
      assertEquals("temporarily_unavailable", JSON.readTree(failed.body()).path("error").asText());
      assertFalse(failed.body().contains(first.refresh()));
    } finally {
      vault(
          "sys/policies/acl/securegate-iam-core",
          Map.of("policy", Files.readString(Path.of("../infra/vault/iam-core.hcl"))));
    }
    assertActive(flow, first.refresh(), true);
    assertNotNull(pair(refresh(flow, first.refresh(), null)));
  }

  @Test
  void introspectionAndRevocationRequireClientAuthenticationAndHideUnknownValues()
      throws Exception {
    for (String path : List.of("/oauth2/introspect", "/oauth2/revoke")) {
      var response = oauthPost(path, null, Map.of("token", "unknown"));
      assertEquals(401, response.statusCode());
      assertEquals("invalid_client", JSON.readTree(response.body()).path("error").asText());
    }
    Flow flow = flow(false);
    assertActive(flow, "unknown-synthetic-token", false);
    var withoutToken = oauthPost("/oauth2/introspect", flow, Map.of());
    assertEquals(400, withoutToken.statusCode());
    assertEquals("invalid_request", JSON.readTree(withoutToken.body()).path("error").asText());
  }

  private record Pair(String access, String refresh) {
    @Override
    public String toString() {
      return "Pair[REDACTED]";
    }
  }

  private Pair initialTokens(Flow flow) throws Exception {
    var browser = browser();
    String code = approve(browser, flow, pending(browser, flow));
    return pair(exchange(flow, code, flow.verifier(), CALLBACK));
  }

  private Pair pair(HttpResponse<String> response) throws Exception {
    assertEquals(200, response.statusCode());
    var body = JSON.readTree(response.body());
    assertTrue(body.has("refresh_token"));
    assertTrue(response.headers().firstValue("Cache-Control").orElseThrow().contains("no-store"));
    return new Pair(body.path("access_token").asText(), body.path("refresh_token").asText());
  }

  private HttpResponse<String> refresh(Flow flow, String refresh, String scope) throws Exception {
    var values = new HashMap<String, String>();
    values.put("grant_type", "refresh_token");
    values.put("refresh_token", refresh);
    if (scope != null) {
      values.put("scope", scope);
    }
    return oauthPost("/oauth2/token", flow, values);
  }

  private HttpResponse<String> oauthPost(String path, Flow client, Map<String, String> values)
      throws Exception {
    var request =
        HttpRequest.newBuilder(uri(path))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("X-Correlation-ID", "lifecycle-test");
    if (client != null) {
      request.header(
          "Authorization",
          "Basic "
              + Base64.getEncoder()
                  .encodeToString(
                      (client.clientId() + ":" + client.secret())
                          .getBytes(StandardCharsets.UTF_8)));
    }
    return HTTP.send(
        request.POST(HttpRequest.BodyPublishers.ofString(form(values))).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private void assertActive(Flow flow, String token, boolean expected) throws Exception {
    var response = oauthPost("/oauth2/introspect", flow, Map.of("token", token));
    assertEquals(200, response.statusCode());
    var body = JSON.readTree(response.body());
    assertEquals(expected, body.path("active").asBoolean());
    if (!expected) {
      assertEquals(1, body.size());
    }
    assertEquals("lifecycle-test", response.headers().firstValue("X-Correlation-ID").orElseThrow());
  }

  private String authorizationId(String access) {
    return jdbc.queryForObject(
        "SELECT id FROM iam_authorization.oauth2_authorization WHERE access_token_value=?",
        String.class,
        access);
  }

  private Flow flow(boolean refreshTokens) throws Exception {
    return flow(refreshTokens, false);
  }

  private Flow flow(boolean refreshTokens, boolean oidc) throws Exception {
    return flow(refreshTokens, oidc, false);
  }

  private Flow flow(boolean refreshTokens, boolean oidc, boolean pam) throws Exception {
    String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    String clientId = "web-" + suffix;
    var registered =
        admin(
            "POST",
            "/api/admin/oauth-clients/browser",
            Map.of(
                "clientId",
                clientId,
                "name",
                "Web <script>alert(1)</script>",
                "audience",
                "browser-api",
                "scopes",
                pam
                    ? List.of("pam:request", "pam:issue")
                    : oidc
                        ? List.of("api:read", "api:write", "profile", "email")
                        : List.of("api:read", "api:write"),
                "redirectUris",
                List.of(CALLBACK),
                "refreshTokens",
                refreshTokens,
                "oidc",
                oidc));
    assertEquals(201, registered.statusCode());
    String email = "user-" + suffix + "@example.test";
    var user =
        admin(
            "POST",
            "/api/admin/users",
            Map.of("email", email, "displayName", "Synthetic User", "password", PASSWORD));
    assertEquals(201, user.statusCode());
    var permit =
        admin(
            "POST",
            "/api/admin/policies",
            Map.of(
                "name",
                "issuance-" + clientId,
                "priority",
                100,
                "enabled",
                true,
                "document",
                Map.of(
                    "schemaVersion",
                    1,
                    "resource",
                    "oauth:" + clientId,
                    "action",
                    "issue",
                    "effect",
                    "ALLOW",
                    "rolesAnyOf",
                    List.of(),
                    "allowedScopes",
                    List.of())));
    assertEquals(201, permit.statusCode(), permit.body());
    return new Flow(
        suffix.toUpperCase(java.util.Locale.ROOT),
        clientId,
        JSON.readTree(registered.body()).path("clientSecret").asText(),
        UUID.fromString(JSON.readTree(user.body()).path("id").asText()),
        email,
        UUID.randomUUID().toString().replace("-", "")
            + UUID.randomUUID().toString().replace("-", ""),
        UUID.randomUUID().toString(),
        oidc);
  }

  @Test
  void serializesConcurrentRedemptionOfTheSameCode() throws Exception {
    Flow flow = flow();
    var browser = browser();
    String code = approve(browser, flow, pending(browser, flow));
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var first =
          executor.submit(() -> exchange(flow, code, flow.verifier(), CALLBACK).statusCode());
      var second =
          executor.submit(() -> exchange(flow, code, flow.verifier(), CALLBACK).statusCode());
      assertEquals(java.util.Set.of(200, 400), java.util.Set.of(first.get(), second.get()));
    }
  }

  @Test
  void expiredPendingConsentCannotBeViewedOrApproved() throws Exception {
    Flow flow = flow();
    var browser = browser();
    var page = pending(browser, flow);
    String state = hidden(page.body(), "state");
    jdbc.update(
        "UPDATE iam_authorization.oauth2_authorization SET attributes=jsonb_set(attributes::jsonb,'{securegate.consent.expires}','0'::jsonb)::text WHERE state=?",
        state);
    assertEquals(
        404,
        get(browser, "/oauth2/consent?client_id=" + flow.clientId() + "&state=" + enc(state))
            .statusCode());
    assertEquals(
        403,
        post(
                browser,
                "/oauth2/authorize",
                Map.of(
                    "client_id",
                    flow.clientId(),
                    "state",
                    state,
                    "scope",
                    "api:read",
                    "_csrf",
                    hidden(page.body(), "_csrf")))
            .statusCode());
  }

  private record Flow(
      String suffix,
      String clientId,
      String secret,
      UUID userId,
      String email,
      String verifier,
      String state,
      boolean oidc) {
    @Override
    public String toString() {
      return "Flow[REDACTED]";
    }
  }

  private String authorize(Flow flow) throws Exception {
    String challenge =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                MessageDigest.getInstance("SHA-256")
                    .digest(flow.verifier().getBytes(StandardCharsets.US_ASCII)));
    return "/oauth2/authorize?response_type=code&client_id="
        + flow.clientId()
        + "&redirect_uri="
        + enc(CALLBACK)
        + "&scope="
        + enc(flow.oidc() ? "openid profile email api:read" : "api:read")
        + "&state="
        + enc(flow.state())
        + "&code_challenge="
        + challenge
        + "&code_challenge_method=S256"
        + (flow.oidc() ? "&nonce=" + enc(flow.state()) : "");
  }

  private HttpResponse<String> pending(HttpClient browser, Flow flow) throws Exception {
    var initial = get(browser, authorize(flow));
    assertEquals(302, initial.statusCode());
    assertTrue(location(initial).contains("/login"));
    var login = get(browser, "/login");
    assertEquals(200, login.statusCode());
    var authenticated =
        post(
            browser,
            "/login",
            Map.of(
                "username",
                flow.email(),
                "password",
                PASSWORD,
                "_csrf",
                hidden(login.body(), "_csrf")));
    assertEquals(302, authenticated.statusCode());
    var consent = get(browser, location(authenticated));
    assertEquals(302, consent.statusCode());
    var page = get(browser, location(consent));
    assertNotNull(
        authorizations.findByToken(
            query(location(consent)).get("state"),
            new org.springframework.security.oauth2.server.authorization.OAuth2TokenType("state")));
    assertEquals(200, page.statusCode());
    return page;
  }

  private String approve(HttpClient browser, Flow flow, HttpResponse<String> page)
      throws Exception {
    return approveScopes(browser, flow, page, List.of("api:read"));
  }

  private String approveScopes(
      HttpClient browser, Flow flow, HttpResponse<String> page, List<String> scopes)
      throws Exception {
    var approved =
        browser.send(
            HttpRequest.newBuilder(uri("/oauth2/authorize"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        form(
                                Map.of(
                                    "client_id",
                                    flow.clientId(),
                                    "state",
                                    hidden(page.body(), "state"),
                                    "_csrf",
                                    hidden(page.body(), "_csrf")))
                            + scopes.stream()
                                .map(scope -> "&scope=" + enc(scope))
                                .collect(java.util.stream.Collectors.joining())))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(302, approved.statusCode());
    var values = query(location(approved));
    assertEquals(flow.state(), values.get("state"));
    assertNotNull(values.get("code"));
    return values.get("code");
  }

  private HttpResponse<String> exchange(Flow flow, String code, String verifier, String redirect)
      throws Exception {
    String basic =
        Base64.getEncoder()
            .encodeToString(
                (flow.clientId() + ":" + flow.secret()).getBytes(StandardCharsets.UTF_8));
    return HTTP.send(
        HttpRequest.newBuilder(uri("/oauth2/token"))
            .header("Authorization", "Basic " + basic)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    form(
                        Map.of(
                            "grant_type",
                            "authorization_code",
                            "code",
                            code,
                            "redirect_uri",
                            redirect,
                            "code_verifier",
                            verifier))))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private void assertError(HttpResponse<String> response, String code) throws Exception {
    assertEquals(400, response.statusCode());
    assertEquals(code, JSON.readTree(response.body()).path("error").asText());
    assertFalse(JSON.readTree(response.body()).has("access_token"));
  }

  private static HttpClient browser() {
    return HttpClient.newBuilder()
        .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
        .build();
  }

  private URI uri(String path) {
    return path.startsWith("http")
        ? URI.create(path)
        : URI.create("http://127.0.0.1:" + port + path);
  }

  private HttpResponse<String> get(HttpClient client, String path) throws Exception {
    return client.send(
        HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> post(HttpClient client, String path, Map<String, String> values)
      throws Exception {
    return client.send(
        HttpRequest.newBuilder(uri(path))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form(values)))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> admin(String method, String path, Object body) throws Exception {
    return HTTP.send(
        HttpRequest.newBuilder(uri(path))
            .header("Authorization", "Bearer " + ADMIN)
            .header("Content-Type", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static String enc(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static String form(Map<String, String> values) {
    return values.entrySet().stream()
        .map(entry -> enc(entry.getKey()) + "=" + enc(entry.getValue()))
        .collect(java.util.stream.Collectors.joining("&"));
  }

  private static String location(HttpResponse<String> response) {
    return response.headers().firstValue("Location").orElseThrow();
  }

  private static Map<String, String> query(String uri) {
    var values = new HashMap<String, String>();
    for (String pair : URI.create(uri).getRawQuery().split("&")) {
      var parts = pair.split("=", 2);
      values.put(
          URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
          parts.length > 1 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "");
    }
    return values;
  }

  private static String hidden(String html, String name) {
    var inputs = Pattern.compile("<input\\b[^>]*>").matcher(html);
    while (inputs.find()) {
      String tag = inputs.group();
      if (tag.contains("name=\"" + name + "\"")) {
        var value = Pattern.compile("value=\"([^\"]*)\"").matcher(tag);
        if (value.find()) {
          return HtmlUtils.htmlUnescape(value.group(1));
        }
      }
    }
    throw new AssertionError("Missing form field: " + name);
  }

  private static String vaultAddress() {
    return "http://" + VAULT.getHost() + ":" + VAULT.getMappedPort(8200);
  }

  private static JsonNode vault(String path, Object body) throws Exception {
    var builder =
        HttpRequest.newBuilder(URI.create(vaultAddress() + "/v1/" + path))
            .header("X-Vault-Token", ROOT)
            .header("Content-Type", "application/json");
    if (body == null) {
      builder.GET();
    } else {
      builder.POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
    }
    var response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    assertTrue(response.statusCode() == 200 || response.statusCode() == 204);
    return response.body().isBlank() ? JSON.createObjectNode() : JSON.readTree(response.body());
  }
}

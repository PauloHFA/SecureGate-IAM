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
  private static final HttpClient HTTP = HttpClient.newHttpClient();
  private static final String ROOT = UUID.randomUUID().toString();
  private static final String ADMIN = UUID.randomUUID().toString();
  private static final String PASSWORD = "Synthetic-password-123!";
  private static final String CALLBACK = "http://127.0.0.1:18090/callback";
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
    registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
    registry.add("spring.datasource.username", DATABASE::getUsername);
    registry.add("spring.datasource.password", DATABASE::getPassword);
    registry.add("iam.transit.enabled", () -> true);
    registry.add("iam.transit.address", BrowserAuthorizationIT::vaultAddress);
    registry.add("iam.transit.role-id-file", () -> credentials.resolve("role").toString());
    registry.add("iam.transit.secret-id-file", () -> credentials.resolve("secret").toString());
    registry.add("iam.authorization.enabled", () -> true);
    registry.add("iam.authorization.issuer", () -> ISSUER);
    registry.add("iam.bootstrap.enabled", () -> true);
    registry.add("iam.bootstrap.token", () -> ADMIN);
  }

  @AfterAll
  static void cleanup() throws Exception {
    if (credentials != null) {
      Files.deleteIfExists(credentials.resolve("role"));
      Files.deleteIfExists(credentials.resolve("secret"));
      Files.deleteIfExists(credentials);
    }
  }

  @Autowired JdbcTemplate jdbc;

  @Autowired
  org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService
      authorizations;

  @LocalServerPort int port;

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
                oidc
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

package com.securegate.e2e;

import static com.securegate.e2e.PropagationEnvironment.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.securegate.events.SecurityProjectionKeys;
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
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.web.util.HtmlUtils;

/** HTTP -> transaction/outbox -> publisher -> RabbitMQ -> enforcer -> Redis -> HTTP. */
class PropagationIT {
  private static final PropagationEnvironment ENV = new PropagationEnvironment();
  private static final String USER_PASSWORD = "Synthetic-propagation-password-123!";
  private static final String CALLBACK = "http://127.0.0.1:18090/callback";
  private static final Path RESULTS = Path.of("target/e2e/propagation.csv");

  @BeforeAll
  static void start() throws Exception {
    ENV.start();
    Files.writeString(
        RESULTS,
        "target,sample,event_id,correlation_id,elapsed_ms,projection_ttl_ms,http_status\n");
    policy("account:self", "read");
    // Explicit low-risk test network/profile: no synthetic proof or deletion of risk projections.
    admin("/api/admin/risk/networks", Map.of("cidr", "127.0.0.0/24"));
    admin("/api/admin/risk/resources", Map.of("resource", "auth:login", "sensitivity", "PUBLIC"));
  }

  @AfterAll
  static void stop() throws Exception {
    ENV.close();
  }

  enum Target {
    ACCESS_TOKEN,
    AUTHORIZATION,
    SESSION
  }

  @ParameterizedTest
  @EnumSource(Target.class)
  void propagatesConfirmedRevocationWithTraceAndRejectsPreviouslyAcceptedToken(Target target)
      throws Exception {
    for (int sample = 1; sample <= 3; sample++) {
      var flow = issue(target != Target.ACCESS_TOKEN);
      String access = flow.tokens.path("access_token").asText();
      if (target == Target.AUTHORIZATION) {
        // The family effect must cover access tokens issued after rotation too.
        flow.tokens =
            json(
                oauth(
                    flow,
                    "/oauth2/token",
                    Map.of(
                        "grant_type",
                        "refresh_token",
                        "refresh_token",
                        flow.tokens.path("refresh_token").asText()),
                    null),
                200);
        access = flow.tokens.path("access_token").asText();
      }
      assertEquals(
          200,
          resource(access).statusCode(),
          "The measured token must be accepted before revocation");
      var claims = JSON.readTree(Base64.getUrlDecoder().decode(access.split("\\.")[1]));
      String kind =
          switch (target) {
            case ACCESS_TOKEN -> "jti";
            case AUTHORIZATION -> "authorization";
            case SESSION -> "sid";
          };
      String subject =
          claims
              .path(
                  switch (target) {
                    case ACCESS_TOKEN -> "jti";
                    case AUTHORIZATION -> "authorization_id";
                    case SESSION -> "sid";
                  })
              .asText();
      assertFalse(subject.isBlank());
      String projection = SecurityProjectionKeys.key("revocation", kind, subject);
      assertFalse(
          Boolean.TRUE.equals(ENV.redis.hasKey(projection)), "No pre-seeded terminal projection");
      await(
          "idle outbox before measurement",
          Duration.ofSeconds(10),
          () ->
              ENV.jdbc.queryForObject(
                      "SELECT count(*) FROM iam_audit.outbox_events WHERE published_at IS NULL",
                      Integer.class)
                  == 0);
      String correlation = UUID.randomUUID().toString();
      HttpResponse<String> confirmed;
      if (target == Target.SESSION) {
        String csrf = hidden(get(flow.browser, "/mfa").body(), "_csrf");
        confirmed = post(flow.browser, "/logout", Map.of("_csrf", csrf), null, correlation);
        assertEquals(302, confirmed.statusCode());
      } else {
        confirmed =
            oauth(
                flow,
                "/oauth2/revoke",
                Map.of(
                    "token",
                    target == Target.ACCESS_TOKEN
                        ? access
                        : flow.tokens.path("refresh_token").asText()),
                correlation);
        assertEquals(200, confirmed.statusCode());
      }
      long start =
          System
              .nanoTime(); // Confirmation received; clock is monotonic and shared by observations.
      String eventType = target == Target.SESSION ? "session.terminated" : "token.revoked";
      var rows =
          ENV.jdbc.queryForList(
              "SELECT event_id,payload::text AS body FROM iam_audit.outbox_events WHERE event_type=? AND payload->>'correlationId'=?",
              eventType,
              correlation);
      assertEquals(1, rows.size(), "One committed producer fact for this operation");
      String eventId = rows.getFirst().get("event_id").toString();
      var fact = JSON.readTree(rows.getFirst().get("body").toString());
      assertEquals(
          subject,
          fact.path("data")
              .path(
                  switch (target) {
                    case ACCESS_TOKEN -> "jti";
                    case AUTHORIZATION -> "authorizationId";
                    case SESSION -> "sid";
                  })
              .asText());
      String token = access;
      await(
          "confirmed publication, Redis event/effect and HTTP rejection",
          Duration.ofSeconds(10),
          () ->
              ENV.jdbc.queryForObject(
                      "SELECT published_at IS NOT NULL FROM iam_audit.outbox_events WHERE event_id=?",
                      Boolean.class,
                      UUID.fromString(eventId))
                  && Boolean.TRUE.equals(
                      ENV.redis.hasKey("securegate:{revocation}:event:" + eventId))
                  && Boolean.TRUE.equals(ENV.redis.hasKey(projection))
                  && resource(token).statusCode() == 401);
      double elapsed = (System.nanoTime() - start) / 1_000_000.0;
      Long ttl = ENV.redis.getExpire(projection, java.util.concurrent.TimeUnit.MILLISECONDS);
      if (target == Target.SESSION) {
        assertEquals(-1L, ttl);
        assertEquals("0", ENV.redis.opsForValue().get(projection));
      } else {
        assertNotNull(ttl);
        assertTrue(ttl > 0);
        assertEquals(
            Long.toString(
                java.time.Instant.parse(fact.path("data").path("expiresAt").asText()).toEpochMilli()
                    + 60_000),
            ENV.redis.opsForValue().get(projection));
      }
      Files.writeString(
          RESULTS,
          String.format(
              java.util.Locale.ROOT,
              "%s,%d,%s,%s,%.3f,%d,401%n",
              target,
              sample,
              eventId,
              correlation,
              elapsed,
              ttl),
          StandardOpenOption.APPEND);
      assertTrue(elapsed < 2000, "Propagation exceeded 2000ms; see target/e2e/propagation.csv");
      assertEquals(401, resource(access).statusCode());
    }
  }

  private static Flow issue(boolean refresh) throws Exception {
    String suffix = UUID.randomUUID().toString().replace("-", "");
    String client = "e2e-" + suffix;
    var registered =
        admin(
            "/api/admin/oauth-clients/browser",
            Map.of(
                "clientId",
                client,
                "name",
                "Propagation test",
                "audience",
                "securegate-api",
                "scopes",
                List.of("api:read"),
                "redirectUris",
                List.of(CALLBACK),
                "refreshTokens",
                refresh,
                "oidc",
                false));
    String email = "e2e-" + suffix + "@example.test";
    admin(
        "/api/admin/users",
        Map.of("email", email, "displayName", "Propagation Test", "password", USER_PASSWORD));
    policy("oauth:" + client, "issue");
    String verifier =
        UUID.randomUUID().toString().replace("-", "")
            + UUID.randomUUID().toString().replace("-", "");
    String challenge =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII)));
    var browser =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .build();
    var flow = new Flow(client, registered.path("clientSecret").asText(), browser);
    var initial =
        get(
            browser,
            "/oauth2/authorize?response_type=code&client_id="
                + client
                + "&redirect_uri="
                + enc(CALLBACK)
                + "&scope=api%3Aread&state="
                + suffix
                + "&code_challenge="
                + challenge
                + "&code_challenge_method=S256");
    assertEquals(302, initial.statusCode());
    var login = get(browser, "/login");
    var authenticated =
        post(
            browser,
            "/login",
            Map.of(
                "username",
                email,
                "password",
                USER_PASSWORD,
                "_csrf",
                hidden(login.body(), "_csrf")),
            null,
            null);
    assertEquals(302, authenticated.statusCode());
    var consent = get(browser, location(authenticated));
    assertEquals(302, consent.statusCode(), "Expected consent navigation, without MFA bypass");
    var page = get(browser, location(consent));
    assertEquals(200, page.statusCode());
    var approved =
        post(
            browser,
            "/oauth2/authorize",
            Map.of(
                "client_id",
                client,
                "state",
                hidden(page.body(), "state"),
                "_csrf",
                hidden(page.body(), "_csrf"),
                "scope",
                "api:read"),
            null,
            null);
    assertEquals(302, approved.statusCode());
    String code = query(location(approved)).get("code");
    assertNotNull(code, "Consent must return a code");
    flow.tokens =
        json(
            oauth(
                flow,
                "/oauth2/token",
                Map.of(
                    "grant_type",
                    "authorization_code",
                    "code",
                    code,
                    "redirect_uri",
                    CALLBACK,
                    "code_verifier",
                    verifier),
                null),
            200);
    return flow;
  }

  private static void policy(String resource, String action) throws Exception {
    admin(
        "/api/admin/policies",
        Map.of(
            "name",
            "e2e-" + UUID.randomUUID(),
            "priority",
            100,
            "enabled",
            true,
            "document",
            Map.of(
                "schemaVersion",
                1,
                "resource",
                resource,
                "action",
                action,
                "effect",
                "ALLOW",
                "rolesAnyOf",
                List.of(),
                "allowedScopes",
                List.of())));
  }

  private static JsonNode admin(String path, Object body) throws Exception {
    var response =
        HTTP.send(
            HttpRequest.newBuilder(ENV.base.resolve(path))
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + ENV.admin)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    return json(response, 201);
  }

  private static HttpResponse<String> resource(String token) throws Exception {
    return HTTP.send(
        HttpRequest.newBuilder(ENV.base.resolve("/api/resources/account"))
            .timeout(Duration.ofSeconds(3))
            .header("Authorization", "Bearer " + token)
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static HttpResponse<String> oauth(
      Flow flow, String path, Map<String, String> body, String correlation) throws Exception {
    String basic =
        Base64.getEncoder()
            .encodeToString((flow.client + ":" + flow.secret).getBytes(StandardCharsets.UTF_8));
    return post(HTTP, path, body, "Basic " + basic, correlation);
  }

  private static HttpResponse<String> post(
      HttpClient client,
      String path,
      Map<String, String> values,
      String authorization,
      String correlation)
      throws Exception {
    var request =
        HttpRequest.newBuilder(ENV.base.resolve(path))
            .timeout(Duration.ofSeconds(5))
            .header("Content-Type", "application/x-www-form-urlencoded");
    if (authorization != null) {
      request.header("Authorization", authorization);
    }
    if (correlation != null) {
      request.header("X-Correlation-ID", correlation);
    }
    String body =
        values.entrySet().stream()
            .map(entry -> enc(entry.getKey()) + "=" + enc(entry.getValue()))
            .collect(java.util.stream.Collectors.joining("&"));
    return client.send(
        request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static HttpResponse<String> get(HttpClient client, String path) throws Exception {
    return client.send(
        HttpRequest.newBuilder(ENV.base.resolve(path)).timeout(Duration.ofSeconds(5)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static JsonNode json(HttpResponse<String> response, int expected) throws Exception {
    assertEquals(
        expected, response.statusCode(), "HTTP operation failed; inspect application logs");
    return JSON.readTree(response.body());
  }

  private static String enc(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static String location(HttpResponse<String> response) {
    return response.headers().firstValue("Location").orElseThrow();
  }

  private static Map<String, String> query(String uri) {
    var values = new HashMap<String, String>();
    for (String part : URI.create(uri).getRawQuery().split("&")) {
      var pair = part.split("=", 2);
      values.put(
          URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
          URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
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

  private static final class Flow {
    final String client;
    final String secret;
    final HttpClient browser;
    JsonNode tokens;

    Flow(String client, String secret, HttpClient browser) {
      this.client = client;
      this.secret = secret;
      this.browser = browser;
    }

    @Override
    public String toString() {
      return "Flow[REDACTED]";
    }
  }
}

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
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.web.util.HtmlUtils;

/** HTTP -> transaction/outbox -> publisher -> RabbitMQ -> enforcer -> Redis -> HTTP. */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PropagationIT {
  private static final PropagationEnvironment ENV = new PropagationEnvironment();
  private static final String USER_PASSWORD = "Synthetic-propagation-password-123!";
  private static final String CALLBACK = "http://127.0.0.1:18090/callback";
  private static final Path RESULTS = Path.of("target/e2e/propagation.csv");
  private static final Path RESILIENCE = Path.of("target/e2e/resilience.csv");

  @BeforeAll
  static void start() throws Exception {
    ENV.start();
    Files.writeString(
        RESULTS,
        "target,sample,event_id,correlation_id,elapsed_ms,projection_ttl_ms,http_status\n");
    Files.writeString(RESILIENCE, "scenario,result\n");
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

  @Test
  @Order(101)
  void pamJourneyRevokesKnownLeaseAfterVaultOutageAndJvmRestartWithCentralAudit() throws Exception {
    ENV.enablePamJourney();
    policy("database:diagnostics", "request");
    policy("database:diagnostics", "issue");
    assertEquals(
        "synthetic_pam_runtime",
        ENV.runtimeJdbc.queryForObject("SELECT current_user", String.class));
    assertDeniedSql(ENV.runtimeJdbc, "SELECT count(*) FROM iam_audit.event_log");
    assertEquals(
        200, pamHttp("GET", auditPath("pam-pool-check"), ENV.admin, null, null).statusCode());
    assertTrue(
        ENV.jdbc.queryForObject(
                "SELECT count(*) FROM pg_stat_activity WHERE usename='synthetic_pam_runtime'",
                Integer.class)
            > 0);
    assertTrue(
        ENV.jdbc.queryForObject(
                "SELECT count(*) FROM pg_stat_activity WHERE usename='securegate_audit_ingest_login'",
                Integer.class)
            > 0);
    assertDeniedSql(ENV.ingestJdbc, "SELECT count(*) FROM iam_audit.outbox_events");
    var flow = pamFlow();
    String requestCorrelation = "pam-request-" + UUID.randomUUID();
    String issueCorrelation = "pam-issue-" + UUID.randomUUID();
    String revokeCorrelation = "pam-revoke-" + UUID.randomUUID();
    String token = flow.tokens.path("access_token").asText();
    var created =
        json(
            pamHttp(
                "POST",
                "/api/pam/requests",
                token,
                Map.of("justification", "Investigate synthetic PAM journey", "ttlSeconds", 120),
                requestCorrelation),
            201);
    String id = created.path("id").asText();
    String path = "/api/pam/requests/" + id + "/credentials";
    var issued = pamHttp("POST", path, token, null, issueCorrelation);
    assertEquals("no-store", issued.headers().firstValue("Cache-Control").orElseThrow());
    var secret = json(issued, 201);
    assertEquals(409, pamHttp("POST", path, token, null, issueCorrelation).statusCode());
    var metadata = pamHttp("GET", path, token, null, null);
    assertEquals(200, metadata.statusCode());
    assertFalse(metadata.body().contains(secret.path("password").asText()));
    assertTrue(
        java.util.Set.of(401, 403)
            .contains(pamHttp("GET", auditPath(issueCorrelation), token, null, null).statusCode()));
    try (var connection =
            java.sql.DriverManager.getConnection(
                ENV.database.getJdbcUrl(),
                secret.path("username").asText(),
                secret.path("password").asText());
        var statement = connection.createStatement()) {
      assertTrue(
          statement.executeQuery("SELECT * FROM securegate_diagnostics.database_health").next());
      var denied =
          assertThrows(
              java.sql.SQLException.class,
              () -> statement.executeQuery("SELECT count(*) FROM iam_identity.users"));
      assertEquals("42501", denied.getSQLState());
      ENV.pauseVault(true);
      try {
        assertEquals(
            202,
            pamHttp(
                    "POST",
                    "/api/admin/pam/requests/" + id + "/revoke",
                    ENV.admin,
                    null,
                    revokeCorrelation)
                .statusCode());
        await(
            "known lease deferred during Vault outage",
            Duration.ofSeconds(15),
            () -> {
              statement.executeQuery("SELECT 1").close();
              return ENV.jdbc.queryForObject(
                      "SELECT cleanup_attempts FROM iam_authorization.privileged_issuances WHERE request_id=?",
                      Integer.class,
                      UUID.fromString(id))
                  > 0;
            });
        assertEquals(
            "REVOKING",
            ENV.jdbc.queryForObject(
                "SELECT state FROM iam_authorization.privileged_issuances WHERE request_id=?",
                String.class,
                UUID.fromString(id)));
        assertEquals(
            0,
            ENV.jdbc.queryForObject(
                "SELECT count(*) FROM iam_audit.security_events WHERE event_type='pam.credential.revoked' AND details->>'requestId'=?",
                Integer.class,
                id));
        ENV.stopIam();
      } finally {
        ENV.pauseVault(false);
      }
      ENV.startIam(Map.of());
      await(
          "known lease cleanup after JVM restart",
          Duration.ofSeconds(70),
          () -> !pamRoleExists(secret.path("username").asText()));
      assertThrows(java.sql.SQLException.class, () -> statement.executeQuery("SELECT 1"));
    }
    await(
        "central PAM revoke fact",
        Duration.ofSeconds(30),
        () -> hasAudit(issueCorrelation, "pam.credential.revoked", id));
    assertTrue(hasAudit(issueCorrelation, "pam.credential.issued", id));
    await(
        "central PAM request facts",
        Duration.ofSeconds(20),
        () ->
            hasAudit(requestCorrelation, "pam.request.authorized", id)
                && hasAudit(revokeCorrelation, "pam.request.revoked", id));
    assertEquals(
        created.path("expiresAt").asText(),
        ENV.jdbc
            .queryForObject(
                "SELECT expires_at FROM iam_authorization.privileged_requests WHERE id=?",
                java.sql.Timestamp.class,
                UUID.fromString(id))
            .toInstant()
            .toString());
    assertTrue(
        java.util.Set.of(401, 403, 409)
            .contains(pamHttp("POST", path, token, null, issueCorrelation).statusCode()));
    assertAuditContainsNoCredential(issueCorrelation, secret, token);
    passed("pam_runtime_isolation_vault_outage_jvm_restart_central_audit");
  }

  @Test
  @Order(102)
  void pamJourneyExpirySurvivesBrokerOutageAndIsQueryableWithCursor() throws Exception {
    ENV.enablePamJourney();
    policy("database:diagnostics", "request");
    policy("database:diagnostics", "issue");
    var flow = pamFlow();
    String token = flow.tokens.path("access_token").asText();
    String requestCorrelation = "pam-expire-request-" + UUID.randomUUID();
    String issueCorrelation = "pam-expire-issue-" + UUID.randomUUID();
    JsonNode secret;
    String id;
    ENV.brokerApplication(false);
    try {
      var created =
          json(
              pamHttp(
                  "POST",
                  "/api/pam/requests",
                  token,
                  Map.of("justification", "Investigate synthetic expiry journey", "ttlSeconds", 30),
                  requestCorrelation),
              201);
      id = created.path("id").asText();
      secret =
          json(
              pamHttp(
                  "POST",
                  "/api/pam/requests/" + id + "/credentials",
                  token,
                  null,
                  issueCorrelation),
              201);
      try (var connection =
              java.sql.DriverManager.getConnection(
                  ENV.database.getJdbcUrl(),
                  secret.path("username").asText(),
                  secret.path("password").asText());
          var statement = connection.createStatement()) {
        assertTrue(
            statement.executeQuery("SELECT * FROM securegate_diagnostics.database_health").next());
        await(
            "credential expires with broker offline",
            Duration.ofSeconds(25),
            () ->
                "REVOKED"
                    .equals(
                        ENV.jdbc.queryForObject(
                            "SELECT state FROM iam_authorization.privileged_issuances WHERE request_id=?",
                            String.class,
                            UUID.fromString(id))));
        assertThrows(java.sql.SQLException.class, () -> statement.executeQuery("SELECT 1"));
      }
      await(
          "request expiration remains in outbox",
          Duration.ofSeconds(35),
          () ->
              ENV.jdbc.queryForObject(
                      "SELECT count(*) FROM iam_audit.outbox_events WHERE event_type='pam.request.expired' AND payload->'data'->>'requestId'=? AND published_at IS NULL",
                      Integer.class,
                      id)
                  == 1);
      assertFalse(hasAudit(issueCorrelation, "pam.credential.issued", id));
      assertEquals(
          0,
          ENV.jdbc.queryForObject(
              "SELECT count(*) FROM iam_audit.event_log WHERE correlation_id=?",
              Integer.class,
              issueCorrelation));
    } finally {
      ENV.brokerApplication(true);
    }
    await(
        "PAM expiry reaches central audit after broker recovery",
        Duration.ofSeconds(90),
        () ->
            hasAudit(issueCorrelation, "pam.credential.issued", id)
                && hasAudit(issueCorrelation, "pam.credential.expired", id)
                && hasAudit(issueCorrelation, "pam.credential.revoked", id)
                && hasAudit(requestCorrelation, "pam.request.authorized", id)
                && hasAudit(requestCorrelation, "pam.request.expired", id));
    assertTrue(hasAudit(issueCorrelation, "pam.credential.issued", id));
    var window = auditPath(issueCorrelation) + "&limit=1";
    var page = json(pamHttp("GET", window, ENV.admin, null, null), 200);
    var seen = new java.util.HashSet<String>();
    while (true) {
      assertEquals(1, page.path("items").size());
      assertTrue(seen.add(page.path("items").get(0).path("id").asText()));
      if (page.path("nextCursor").isNull()) {
        break;
      }
      page =
          json(
              pamHttp(
                  "GET",
                  window + "&cursor=" + enc(page.path("nextCursor").asText()),
                  ENV.admin,
                  null,
                  null),
              200);
    }
    assertEquals(3, seen.size());
    assertAuditContainsNoCredential(issueCorrelation, secret, token);
    assertFalse(pamRoleExists(secret.path("username").asText()));
    passed("pam_expiration_broker_outage_recovery_central_query_cursor");
  }

  private static Flow pamFlow() throws Exception {
    var flow = issue(false, List.of("pam:request", "pam:issue"));
    String token = flow.tokens.path("access_token").asText();
    assertEquals(
        403,
        pamHttp(
                "POST",
                "/api/pam/requests",
                token,
                Map.of("justification", "MFA must be enforced in the journey", "ttlSeconds", 120),
                "pam-before-mfa-" + UUID.randomUUID())
            .statusCode());
    var enrollment =
        post(
            flow.browser,
            "/mfa/enroll",
            Map.of(
                "password",
                USER_PASSWORD,
                "_csrf",
                hidden(get(flow.browser, "/mfa").body(), "_csrf")),
            null,
            null);
    assertEquals(200, enrollment.statusCode());
    var matcher = Pattern.compile("id=\"totp-secret\">([A-Z2-7]{32})").matcher(enrollment.body());
    assertTrue(matcher.find(), "Enrollment must expose its one-time seed");
    String seed = matcher.group(1), alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    var bits = new StringBuilder();
    for (char letter : seed.toCharArray()) {
      bits.append(
          String.format("%5s", Integer.toBinaryString(alphabet.indexOf(letter))).replace(' ', '0'));
    }
    byte[] key = new byte[20];
    for (int index = 0; index < 20; index++) {
      key[index] = (byte) Integer.parseInt(bits.substring(index * 8, index * 8 + 8), 2);
    }
    var hmac = javax.crypto.Mac.getInstance("HmacSHA1");
    hmac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA1"));
    byte[] digest =
        hmac.doFinal(
            java.nio.ByteBuffer.allocate(8)
                .putLong(java.time.Instant.now().getEpochSecond() / 30)
                .array());
    int offset = digest[19] & 15;
    int number = java.nio.ByteBuffer.wrap(digest, offset, 4).getInt() & 0x7fffffff;
    String code = String.format(java.util.Locale.ROOT, "%06d", number % 1_000_000);
    assertEquals(
        200,
        post(
                flow.browser,
                "/mfa/confirm",
                Map.of("code", code, "_csrf", hidden(get(flow.browser, "/mfa").body(), "_csrf")),
                null,
                null)
            .statusCode());
    return flow;
  }

  private static HttpResponse<String> pamHttp(
      String method, String path, String token, Object body, String correlation) throws Exception {
    var request = HttpRequest.newBuilder(ENV.base.resolve(path)).timeout(Duration.ofSeconds(5));
    if (token != null) {
      request.header("Authorization", "Bearer " + token);
    }
    if (correlation != null) {
      request.header("X-Correlation-ID", correlation);
    }
    request.header("Content-Type", "application/json");
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

  private static String auditPath(String correlation) {
    var now = java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    return "/api/admin/audit/events?from="
        + enc(now.minusSeconds(600).toString())
        + "&to="
        + enc(now.plusSeconds(60).toString())
        + "&correlationId="
        + enc(correlation);
  }

  private static boolean hasAudit(String correlation, String type, String id) throws Exception {
    var page = json(pamHttp("GET", auditPath(correlation), ENV.admin, null, null), 200);
    for (var item : page.path("items")) {
      var event = item.path("event");
      if (event.path("eventType").asText().equals(type)
          && event.path("data").path("requestId").asText().equals(id)) {
        return true;
      }
    }
    return false;
  }

  private static boolean pamRoleExists(String username) {
    return ENV.jdbc.queryForObject(
        "SELECT EXISTS(SELECT 1 FROM pg_roles WHERE rolname=?)", Boolean.class, username);
  }

  private static void assertDeniedSql(
      org.springframework.jdbc.core.JdbcTemplate connection, String sql) {
    var failure =
        assertThrows(
            org.springframework.dao.DataAccessException.class,
            () -> connection.queryForObject(sql, Integer.class));
    assertEquals("42501", ((java.sql.SQLException) failure.getMostSpecificCause()).getSQLState());
  }

  private static void assertAuditContainsNoCredential(
      String correlation, JsonNode secret, String token) throws Exception {
    String central = pamHttp("GET", auditPath(correlation), ENV.admin, null, null).body();
    String outbox =
        ENV.jdbc.queryForObject(
            "SELECT string_agg(payload::text,' ') FROM iam_audit.outbox_events WHERE payload->>'correlationId'=?",
            String.class,
            correlation);
    for (String sensitive :
        List.of(secret.path("password").asText(), secret.path("username").asText(), token)) {
      assertFalse(central.contains(sensitive));
      assertFalse(outbox.contains(sensitive));
    }
  }

  @Test
  @Order(100)
  void pamRequestObserverRecoversAfterAuditFailureAndRealJvmRestart() throws Exception {
    ENV.stopIam();
    UUID user = UUID.randomUUID(), request = UUID.randomUUID();
    var expiry =
        java.time.Instant.now().minusSeconds(1).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    ENV.jdbc.update(
        "INSERT INTO iam_identity.users(id,email,display_name,status) VALUES (?,?,?,'ACTIVE')",
        user,
        user + "@example.test",
        "Synthetic PAM operator");
    ENV.jdbc.update(
        """
        INSERT INTO iam_authorization.privileged_requests
        (id,user_id,target,justification,created_at,expires_at,origin_correlation_id)
        VALUES (?,?,'database:diagnostics','Synthetic restart diagnostic',?,?, 'pam-jvm-restart')
        """,
        request,
        user,
        java.sql.Timestamp.from(expiry.minusSeconds(60)),
        java.sql.Timestamp.from(expiry));
    ENV.jdbc.execute(
        "ALTER TABLE iam_audit.security_events ADD CONSTRAINT synthetic_pam_audit_outage CHECK (event_type <> 'pam.request.expired') NOT VALID");
    try {
      ENV.startIam(Map.of("IAM_PAM_ENABLED", "true"));
      await(
          "durable observation retry",
          Duration.ofSeconds(15),
          () ->
              ENV.jdbc.queryForObject(
                      "SELECT observation_attempts FROM iam_authorization.privileged_requests WHERE id=?",
                      Integer.class,
                      request)
                  > 0);
      assertNull(
          ENV.jdbc.queryForObject(
              "SELECT expiration_recorded_at FROM iam_authorization.privileged_requests WHERE id=?",
              java.sql.Timestamp.class,
              request));
      long previousPid = ENV.iamPid();
      ENV.stopIam();
      int attempts =
          ENV.jdbc.queryForObject(
              "SELECT observation_attempts FROM iam_authorization.privileged_requests WHERE id=?",
              Integer.class,
              request);
      var scheduled =
          ENV.jdbc.queryForObject(
              "SELECT next_observation_at FROM iam_authorization.privileged_requests WHERE id=?",
              java.sql.Timestamp.class,
              request);
      ENV.jdbc.execute(
          "ALTER TABLE iam_audit.security_events DROP CONSTRAINT synthetic_pam_audit_outage");
      ENV.startIam(Map.of());
      assertNotEquals(previousPid, ENV.iamPid());
      await(
          "expiration after JVM restart",
          Duration.ofSeconds(70),
          () ->
              ENV.jdbc.queryForObject(
                      "SELECT count(*) FROM iam_audit.security_events WHERE event_type='pam.request.expired' AND details->>'requestId'=?",
                      Integer.class,
                      request.toString())
                  == 1);
      assertEquals(
          attempts,
          ENV.jdbc.queryForObject(
              "SELECT observation_attempts FROM iam_authorization.privileged_requests WHERE id=?",
              Integer.class,
              request));
      assertEquals(
          scheduled,
          ENV.jdbc.queryForObject(
              "SELECT next_observation_at FROM iam_authorization.privileged_requests WHERE id=?",
              java.sql.Timestamp.class,
              request));
      assertEquals(
          expiry,
          ENV.jdbc
              .queryForObject(
                  "SELECT expires_at FROM iam_authorization.privileged_requests WHERE id=?",
                  java.sql.Timestamp.class,
                  request)
              .toInstant());
      assertEquals(
          "pam-jvm-restart",
          ENV.jdbc.queryForObject(
              "SELECT correlation_id FROM iam_audit.security_events WHERE event_type='pam.request.expired' AND details->>'requestId'=?",
              String.class,
              request.toString()));
      assertEquals(
          1,
          ENV.jdbc.queryForObject(
              "SELECT count(*) FROM iam_audit.outbox_events WHERE event_type='pam.request.expired' AND payload->'data'->>'requestId'=?",
              Integer.class,
              request.toString()));
      var metrics =
          HTTP.send(
              HttpRequest.newBuilder(
                      ENV.base.resolve("/actuator/metrics/iam.pam.expiration.pending"))
                  .header("Authorization", "Bearer " + ENV.admin)
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, metrics.statusCode());
      passed("pam_observer_audit_outage_and_real_jvm_restart");
    } finally {
      ENV.jdbc.execute(
          "ALTER TABLE iam_audit.security_events DROP CONSTRAINT IF EXISTS synthetic_pam_audit_outage");
    }
  }

  @ParameterizedTest
  @Order(1)
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

  @Test
  @Order(2)
  void stoppedEnforcerRetainsPublishedRevocationAndExactReplayDoesNotChangeItsEffect()
      throws Exception {
    var flow = issue(false);
    String token = access(flow);
    var claims = claims(token);
    String key = SecurityProjectionKeys.key("revocation", "jti", claims.path("jti").asText());
    assertEquals(200, resource(token).statusCode());
    ENV.stopEnforcer();
    JsonNode fact;
    try {
      fact = revoke(flow, token);
      published(fact);
      assertFalse(Boolean.TRUE.equals(ENV.redis.hasKey(key)));
      assertTrue(queueDepth("enforcer.revocation") > 0);
    } finally {
      ENV.restartEnforcer(Map.of());
    }
    converged(fact, key, token);
    String value = ENV.redis.opsForValue().get(key);
    String eventMarker = "securegate:{revocation}:event:" + fact.path("eventId").asText();
    String fingerprint = ENV.redis.opsForValue().get(eventMarker);
    var wire = ENV.capture(fact.path("eventId").asText());
    ENV.replay(wire, 3);
    // A later real logout in the same FIFO queue fences processing of all preceding copies.
    var closed = logout(flow);
    String sidKey = SecurityProjectionKeys.key("revocation", "sid", claims.path("sid").asText());
    converged(closed, sidKey, token);
    assertEquals(value, ENV.redis.opsForValue().get(key));
    assertEquals(fingerprint, ENV.redis.opsForValue().get(eventMarker));
    assertEquals(0, queueDepth("enforcer.revocation.dlq"));
    passed("enforcer_restart_and_exact_revocation_replay");
  }

  @Test
  @Order(3)
  void brokerOutageKeepsTheCommittedFactPendingAndRecoversWithoutAnotherRevocation()
      throws Exception {
    var flow = issue(false);
    String token = access(flow);
    String key =
        SecurityProjectionKeys.key("revocation", "jti", claims(token).path("jti").asText());
    assertEquals(200, resource(token).statusCode());
    ENV.brokerApplication(false);
    JsonNode fact;
    try {
      fact = revoke(flow, token);
      String id = fact.path("eventId").asText();
      await(
          "persisted publication failure",
          Duration.ofSeconds(15),
          () ->
              ENV.jdbc.queryForObject(
                  "SELECT published_at IS NULL AND last_error_code IS NOT NULL FROM iam_audit.outbox_events WHERE event_id=?",
                  Boolean.class,
                  UUID.fromString(id)));
      assertFalse(Boolean.TRUE.equals(ENV.redis.hasKey(key)));
    } finally {
      ENV.brokerApplication(true);
    }
    converged(fact, key, token);
    assertEquals(
        1,
        ENV.jdbc.queryForObject(
            "SELECT count(*) FROM iam_audit.outbox_events WHERE event_id=?",
            Integer.class,
            UUID.fromString(fact.path("eventId").asText())));
    passed("broker_outage_and_persisted_retry");
  }

  @Test
  @Order(4)
  void redisOutageFailsResourceClosedAndLeavesDeliveryRecoverable() throws Exception {
    var flow = issue(false);
    String token = access(flow);
    String key =
        SecurityProjectionKeys.key("revocation", "jti", claims(token).path("jti").asText());
    assertEquals(200, resource(token).statusCode());
    ENV.pauseRedis(true);
    JsonNode fact;
    try {
      var unavailable = resource(token);
      assertEquals(503, unavailable.statusCode());
      assertFalse(unavailable.body().contains("Redis"));
      fact = revoke(flow, token);
      published(fact);
      Thread.sleep(3000); // Longer than the real enforcer command timeout; Redis remains paused.
      await(
          "delivery remains pending while Redis is paused",
          Duration.ofSeconds(5),
          () -> queueDepth("enforcer.revocation") > 0);
    } finally {
      ENV.pauseRedis(false);
    }
    converged(fact, key, token);
    assertEquals(0, queueDepth("enforcer.revocation.dlq"));
    passed("redis_outage_fail_closed_and_delivery_recovery");
  }

  @Test
  @Order(5)
  void olderPolicyEventsDeliveredAfterNewerAllowCannotReintroduceStaleDenial() throws Exception {
    var flow = issue(false);
    String token = access(flow);
    assertEquals(200, resource(token).statusCode());
    var initial = policy("account:self", "read");
    String policyId = initial.path("id").asText();
    var created = policyFact(policyId, 0);
    consumedBlock(created);
    var olderAllow = ENV.capture(created.path("eventId").asText());
    var denied = updatePolicy(initial, "DENY");
    var deniedFact = policyFact(policyId, denied.path("version").asLong());
    consumedBlock(deniedFact);
    assertEquals(403, resource(token).statusCode());
    var olderDeny = ENV.capture(deniedFact.path("eventId").asText());
    var allowed = updatePolicy(denied, "ALLOW");
    consumedBlock(policyFact(policyId, allowed.path("version").asLong()));
    String key = SecurityProjectionKeys.key("block-policy", "policy", policyId);
    String fresh = ENV.redis.opsForValue().get(key);
    assertTrue(
        fresh.startsWith(
            String.format(java.util.Locale.ROOT, "%019d|true|", allowed.path("version").asLong())));
    assertEquals(200, resource(token).statusCode());
    ENV.replay(olderDeny, 2);
    ENV.replay(olderAllow, 2);
    blockFence();
    assertEquals(fresh, ENV.redis.opsForValue().get(key));
    assertEquals(200, resource(token).statusCode());
    assertEquals(0, queueDepth("enforcer.block-policy.dlq"));
    passed("out_of_order_policy_replay_preserves_newer_allow");
  }

  @Test
  @Order(99)
  void realFailuresDeduplicateAndReachAccountAndIpLimitsThenExpireNaturally() throws Exception {
    // Only the block duration changes for this final scenario; defaults 5/20 and window 300s stay.
    ENV.restartEnforcer(Map.of("ENFORCER_BLOCKPOLICY_BLOCKSECONDS", "3"));
    var flow = issue(true);
    String token = access(flow);
    var claims = claims(token);
    String user = claims.path("sub").asText();
    long version =
        ENV.jdbc.queryForObject(
            "SELECT version FROM iam_identity.users WHERE id=?", Long.class, UUID.fromString(user));
    String subject = user + ":" + version;
    String counter = SecurityProjectionKeys.key("block-policy", "attempts:account", subject);
    String account = SecurityProjectionKeys.key("block-policy", "blocked:account", subject);
    String ip = SecurityProjectionKeys.key("block-policy", "blocked:ip", "127.0.0.1");
    var browser =
        HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .build();
    String correlation = UUID.randomUUID().toString();
    assertEquals(
        302, login(browser, flow.email, "synthetic-wrong-password", correlation).statusCode());
    var failure = fact("auth.login.failed", correlation);
    consumedBlock(failure);
    var original = ENV.capture(failure.path("eventId").asText());
    ENV.replay(original, 3);
    blockFence();
    assertEquals(1L, ENV.redis.opsForZSet().zCard(counter));
    assertFalse(Boolean.TRUE.equals(ENV.redis.hasKey(account)));
    for (int i = 0; i < 4; i++) {
      assertEquals(302, login(browser, flow.email, "synthetic-wrong-password", null).statusCode());
    }
    await(
        "account threshold projection",
        Duration.ofSeconds(10),
        () -> Boolean.TRUE.equals(ENV.redis.hasKey(account)));
    assertEquals(5L, ENV.redis.opsForZSet().zCard(counter));
    assertEquals(429, resource(token).statusCode());
    var deniedLogin = login(browser, flow.email, USER_PASSWORD, null);
    assertEquals(302, deniedLogin.statusCode());
    assertEquals(ENV.base.resolve("/login?error").toString(), location(deniedLogin));
    assertEquals(302, get(browser, "/mfa").statusCode());
    var refused =
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
            400);
    assertEquals("invalid_grant", refused.path("error").asText());
    await(
        "natural account block expiry",
        Duration.ofSeconds(12),
        () ->
            !Boolean.TRUE.equals(ENV.redis.hasKey(account)) && resource(token).statusCode() == 200);
    // Retained failure history counts toward the default shared IP threshold of twenty.
    for (int i = 0; i < 25 && !Boolean.TRUE.equals(ENV.redis.hasKey(ip)); i++) {
      var attempt =
          login(
              browser,
              "unknown-" + UUID.randomUUID() + "@example.test",
              "synthetic-wrong-password",
              null);
      assertTrue(attempt.statusCode() == 302 || attempt.statusCode() == 429);
    }
    await(
        "IP threshold projection",
        Duration.ofSeconds(10),
        () -> Boolean.TRUE.equals(ENV.redis.hasKey(ip)));
    assertEquals(
        20L,
        ENV.redis
            .opsForZSet()
            .zCard(SecurityProjectionKeys.key("block-policy", "attempts:ip", "127.0.0.1")));
    int before = failureCount();
    assertEquals(429, login(browser, flow.email, USER_PASSWORD, null).statusCode());
    assertEquals(
        429, login(browser, "unknown@example.test", "synthetic-wrong-password", null).statusCode());
    assertEquals(before, failureCount());
    assertEquals(429, resource(token).statusCode());
    assertEquals(
        "invalid_grant",
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
                400)
            .path("error")
            .asText());
    await(
        "natural IP block expiry",
        Duration.ofSeconds(12),
        () -> !Boolean.TRUE.equals(ENV.redis.hasKey(ip)) && resource(token).statusCode() == 200);
    assertEquals(
        200,
        oauth(
                flow,
                "/oauth2/token",
                Map.of(
                    "grant_type",
                    "refresh_token",
                    "refresh_token",
                    flow.tokens.path("refresh_token").asText()),
                null)
            .statusCode());
    assertEquals(0, queueDepth("enforcer.block-policy.dlq"));
    passed("real_account_ip_thresholds_replay_and_expiry");
  }

  private static JsonNode claims(String token) throws Exception {
    return JSON.readTree(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
  }

  private static String access(Flow flow) {
    return flow.tokens.path("access_token").asText();
  }

  private static JsonNode fact(String type, String correlation) throws Exception {
    var rows =
        ENV.jdbc.queryForList(
            "SELECT payload::text FROM iam_audit.outbox_events WHERE event_type=? AND payload->>'correlationId'=?",
            String.class,
            type,
            correlation);
    assertEquals(1, rows.size());
    return JSON.readTree(rows.getFirst());
  }

  private static JsonNode revoke(Flow flow, String token) throws Exception {
    String correlation = UUID.randomUUID().toString();
    assertEquals(
        200, oauth(flow, "/oauth2/revoke", Map.of("token", token), correlation).statusCode());
    return fact("token.revoked", correlation);
  }

  private static JsonNode logout(Flow flow) throws Exception {
    String correlation = UUID.randomUUID().toString();
    assertEquals(
        302,
        post(
                flow.browser,
                "/logout",
                Map.of("_csrf", hidden(get(flow.browser, "/mfa").body(), "_csrf")),
                null,
                correlation)
            .statusCode());
    return fact("session.terminated", correlation);
  }

  private static void published(JsonNode fact) throws Exception {
    await(
        "confirmed outbox publication",
        Duration.ofSeconds(45),
        () ->
            ENV.jdbc.queryForObject(
                "SELECT published_at IS NOT NULL FROM iam_audit.outbox_events WHERE event_id=?",
                Boolean.class,
                UUID.fromString(fact.path("eventId").asText())));
  }

  private static void converged(JsonNode fact, String key, String token) throws Exception {
    published(fact);
    await(
        "recovery projection and HTTP rejection",
        Duration.ofSeconds(45),
        () ->
            Boolean.TRUE.equals(
                    ENV.redis.hasKey(
                        "securegate:{revocation}:event:" + fact.path("eventId").asText()))
                && Boolean.TRUE.equals(ENV.redis.hasKey(key))
                && resource(token).statusCode() == 401);
  }

  private static int queueDepth(String queue) throws Exception {
    try (var connection = ENV.rabbitConnection();
        var channel = connection.createChannel()) {
      return channel.queueDeclarePassive(queue).getMessageCount();
    }
  }

  private static JsonNode policyFact(String id, long version) throws Exception {
    var rows =
        ENV.jdbc.queryForList(
            "SELECT payload::text FROM iam_audit.outbox_events WHERE event_type='policy.changed' AND payload->'data'->>'policyId'=? AND payload->'data'->>'version'=?",
            String.class,
            id,
            Long.toString(version));
    assertEquals(1, rows.size());
    return JSON.readTree(rows.getFirst());
  }

  private static void consumedBlock(JsonNode fact) throws Exception {
    published(fact);
    await(
        "block/policy effect completion",
        Duration.ofSeconds(15),
        () ->
            Boolean.TRUE.equals(
                ENV.redis.hasKey(
                    "securegate:{block-policy}:event:" + fact.path("eventId").asText())));
  }

  private static void blockFence() throws Exception {
    var created = policy("e2e:fence", "read");
    consumedBlock(policyFact(created.path("id").asText(), 0));
  }

  private static JsonNode updatePolicy(JsonNode policy, String effect) throws Exception {
    var document = policy.path("document").deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) document).put("effect", effect);
    var response =
        HTTP.send(
            HttpRequest.newBuilder(
                    ENV.base.resolve("/api/admin/policies/" + policy.path("id").asText()))
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + ENV.admin)
                .header("Content-Type", "application/json")
                .PUT(
                    HttpRequest.BodyPublishers.ofString(
                        JSON.writeValueAsString(
                            Map.of(
                                "name",
                                policy.path("name").asText(),
                                "priority",
                                policy.path("priority").asInt(),
                                "enabled",
                                true,
                                "document",
                                document,
                                "version",
                                policy.path("version").asLong()))))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    return json(response, 200);
  }

  private static HttpResponse<String> login(
      HttpClient browser, String email, String password, String correlation) throws Exception {
    return post(
        browser,
        "/login",
        Map.of(
            "username",
            email,
            "password",
            password,
            "_csrf",
            hidden(get(browser, "/login").body(), "_csrf")),
        null,
        correlation);
  }

  private static int failureCount() {
    return ENV.jdbc.queryForObject(
        "SELECT count(*) FROM iam_audit.outbox_events WHERE event_type='auth.login.failed'",
        Integer.class);
  }

  private static void passed(String scenario) throws Exception {
    Files.writeString(RESILIENCE, scenario + ",PASS\n", StandardOpenOption.APPEND);
  }

  private static Flow issue(boolean refresh) throws Exception {
    return issue(refresh, List.of("api:read"));
  }

  private static Flow issue(boolean refresh, List<String> scopes) throws Exception {
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
                scopes,
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
    var flow = new Flow(client, registered.path("clientSecret").asText(), browser, email);
    var initial =
        get(
            browser,
            "/oauth2/authorize?response_type=code&client_id="
                + client
                + "&redirect_uri="
                + enc(CALLBACK)
                + "&scope="
                + enc(String.join(" ", scopes))
                + "&state="
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
                String.join(" ", scopes)),
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

  private static JsonNode policy(String resource, String action) throws Exception {
    return admin(
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
            .map(
                entry ->
                    entry.getKey().equals("scope")
                        ? java.util.Arrays.stream(entry.getValue().split(" "))
                            .map(scope -> "scope=" + enc(scope))
                            .collect(java.util.stream.Collectors.joining("&"))
                        : enc(entry.getKey()) + "=" + enc(entry.getValue()))
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
    final String email;
    JsonNode tokens;

    Flow(String client, String secret, HttpClient browser, String email) {
      this.client = client;
      this.secret = secret;
      this.browser = browser;
      this.email = email;
    }

    @Override
    public String toString() {
      return "Flow[REDACTED]";
    }
  }
}

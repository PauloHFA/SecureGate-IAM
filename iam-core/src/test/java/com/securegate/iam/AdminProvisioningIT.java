package com.securegate.iam;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securegate.iam.audit.application.port.AuditTrail;
import com.securegate.iam.identity.application.port.UserRepository;
import com.securegate.iam.identity.domain.User;
import com.securegate.iam.shared.application.ConcurrentUpdateException;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminProvisioningIT {
  private static final String TOKEN = UUID.randomUUID().toString() + UUID.randomUUID();
  private static final String PASSWORD = "Very-safe-test-password!";

  @Container
  static final PostgreSQLContainer<?> DATABASE =
      new PostgreSQLContainer<>("postgres:16.15-alpine")
          .withDatabaseName("provisioning_test")
          .withUsername("provisioning_test")
          .withPassword(UUID.randomUUID().toString());

  @DynamicPropertySource
  static void configuration(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
    registry.add("spring.datasource.username", DATABASE::getUsername);
    registry.add("spring.datasource.password", DATABASE::getPassword);
    registry.add("iam.bootstrap.enabled", () -> true);
    registry.add("iam.bootstrap.token", () -> TOKEN);
  }

  @LocalServerPort int port;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate jdbc;
  @Autowired UserRepository users;
  @MockitoSpyBean AuditTrail audit;
  @MockitoSpyBean com.securegate.iam.audit.infrastructure.JdbcOutboxEvents outbox;
  @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
  private final HttpClient client = HttpClient.newHttpClient();

  @Test
  void commitsAuditAndCanonicalOutboxTogetherWithMatchingIdsAndTimestamps() throws Exception {
    var user =
        successful(
            call(
                "POST",
                "/users",
                Map.of("email", email(), "displayName", "Outbox", "password", PASSWORD)),
            201);
    String id = user.path("id").asText();
    var row =
        jdbc.queryForMap(
            """
        SELECT a.id,a.occurred_at,a.event_type,a.actor,a.details::text,o.payload::text,o.published_at,
          o.occurred_at AS event_time FROM iam_audit.security_events a
        JOIN iam_audit.outbox_events o ON o.event_id=a.id
        WHERE a.resource=? AND a.event_type='identity.user.created'
        """,
            id);
    var envelope =
        json.readValue((String) row.get("payload"), com.securegate.events.SecurityEvent.class);
    assertEquals(row.get("id"), envelope.eventId());
    assertEquals("identity.user.created", envelope.eventType());
    assertEquals("iam-core", envelope.producer());
    assertEquals("admin-it", envelope.correlationId());
    assertEquals(id, envelope.context().get("resource"));
    assertEquals(row.get("actor"), envelope.actor());
    assertEquals(1, envelope.schemaVersion());
    assertEquals(row.get("occurred_at"), row.get("event_time"));
    assertEquals(((java.sql.Timestamp) row.get("occurred_at")).toInstant(), envelope.occurredAt());
    assertNull(row.get("published_at"));
    assertFalse(((String) row.get("payload")).contains(PASSWORD));
    assertEquals(Set.of("status", "version"), envelope.data().keySet());
  }

  @Test
  void outboxFailureRollsBackUserCredentialsAndAudit() throws Exception {
    String correlation = "outbox-failure-" + unique();
    doThrow(new IllegalStateException("synthetic outbox failure"))
        .when(outbox)
        .append(argThat(event -> event.correlationId().equals(correlation)));
    String email = email();
    var response =
        client.send(
            request(
                "POST",
                "/users",
                Map.of("email", email, "displayName", "Rollback outbox", "password", PASSWORD),
                TOKEN,
                correlation),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(500, response.statusCode());
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM iam_identity.users WHERE email=?", Integer.class, email));
    assertNoEvent(correlation);
  }

  @Test
  void directAuditStillHasAtomicPairAndRequiresTransactionalOutboxAppend() {
    String correlation = "standalone-" + unique();
    var record =
        new com.securegate.iam.audit.application.port.AuditRecord(
            new com.securegate.events.SecurityEventType("policy.changed"),
            "synthetic-policy",
            new com.securegate.iam.shared.application.port.OperationContext("test", correlation),
            Map.of("version", "2"));
    doThrow(new IllegalStateException("synthetic append failure"))
        .when(outbox)
        .append(argThat(event -> event.correlationId().equals(correlation)));
    assertThrows(IllegalStateException.class, () -> audit.record(record));
    assertNoEvent(correlation);
    var envelope =
        new com.securegate.events.SecurityEvent(
            UUID.randomUUID(),
            "policy.changed",
            1,
            java.time.Instant.now(),
            "iam-core",
            "outside-transaction",
            "test",
            Map.of("resource", "synthetic"),
            Map.of());
    assertThrows(IllegalStateException.class, () -> outbox.append(envelope));
  }

  @Test
  void surroundingRollbackAndUncommittedVisibilityPreserveAtomicity() {
    String correlation = "outer-" + unique();
    var record =
        new com.securegate.iam.audit.application.port.AuditRecord(
            new com.securegate.events.SecurityEventType("policy.changed"),
            "synthetic-policy",
            new com.securegate.iam.shared.application.port.OperationContext("test", correlation),
            Map.of("version", "3"));
    var transactions =
        new org.springframework.transaction.support.TransactionTemplate(transactionManager);
    transactions.executeWithoutResult(
        status -> {
          audit.record(record);
          assertEquals(1, pendingEvents(correlation));
          assertEquals(0, CompletableFuture.supplyAsync(() -> pendingEvents(correlation)).join());
          status.setRollbackOnly();
        });
    assertNoEvent(correlation);
    transactions.executeWithoutResult(status -> audit.record(record));
    assertEquals(1, pendingEvents(correlation));
  }

  @Test
  void outboxRejectsDuplicateIdsPayloadMutationAndNullHeaders() throws Exception {
    String correlation = "immutable-" + unique();
    audit.record(
        new com.securegate.iam.audit.application.port.AuditRecord(
            new com.securegate.events.SecurityEventType("policy.changed"),
            "synthetic-policy",
            new com.securegate.iam.shared.application.port.OperationContext("test", correlation),
            Map.of("version", "1")));
    UUID id =
        jdbc.queryForObject(
            "SELECT event_id FROM iam_audit.outbox_events WHERE payload->>'correlationId'=?",
            UUID.class,
            correlation);
    assertThrows(
        DataAccessException.class,
        () ->
            jdbc.update(
                "UPDATE iam_audit.outbox_events SET payload=jsonb_set(payload,'{actor}','\"tampered\"') WHERE event_id=?",
                id));
    assertThrows(
        DataAccessException.class,
        () ->
            jdbc.update(
                "INSERT INTO iam_audit.outbox_events SELECT * FROM iam_audit.outbox_events WHERE event_id=?",
                id));
    // New audit id, but an explicit JSON null must not evade PostgreSQL CHECK semantics.
    UUID newId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO iam_audit.security_events(id,actor,event_type,resource,correlation_id,details) VALUES (?,'test','policy.changed','synthetic',?,'{}')",
        newId,
        "malformed-" + unique());
    assertThrows(
        DataAccessException.class,
        () ->
            jdbc.update(
                """
        INSERT INTO iam_audit.outbox_events(event_id,event_type,schema_version,occurred_at,payload)
        SELECT ?,'policy.changed',1,occurred_at,
          jsonb_set(jsonb_set(payload,'{eventId}',to_jsonb(?::text)),'{eventType}','null')
        FROM iam_audit.outbox_events WHERE event_id=?
        """,
                newId,
                newId.toString(),
                id));
    assertEquals(1, pendingEvents(correlation));
  }

  private int pendingEvents(String correlation) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM iam_audit.outbox_events WHERE payload->>'correlationId'=? AND published_at IS NULL",
        Integer.class,
        correlation);
  }

  private void assertNoEvent(String correlation) {
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM iam_audit.security_events WHERE correlation_id=?",
            Integer.class,
            correlation));
    assertEquals(0, pendingEvents(correlation));
  }

  private String unique() {
    return UUID.randomUUID().toString().replace("-", "");
  }

  private String email() {
    return "u" + unique() + "@example.test";
  }

  private HttpRequest request(
      String method, String path, Object body, String credential, String correlation)
      throws Exception {
    var builder =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/admin" + path))
            .header("X-Correlation-ID", correlation);
    if (credential != null) {
      builder.header("Authorization", "Bearer " + credential);
    }
    if (body != null) {
      builder.header("Content-Type", "application/json");
    }
    return builder
        .method(
            method,
            body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
        .build();
  }

  private HttpResponse<String> call(String method, String path, Object body) throws Exception {
    return client.send(
        request(method, path, body, TOKEN, "admin-it"), HttpResponse.BodyHandlers.ofString());
  }

  private JsonNode successful(HttpResponse<String> response, int status) throws Exception {
    assertEquals(status, response.statusCode(), response.body());
    assertFalse(response.body().contains(PASSWORD));
    assertFalse(response.body().contains("$argon2id$"));
    return response.body().isEmpty() ? json.nullNode() : json.readTree(response.body());
  }

  private JsonNode createUser() throws Exception {
    return successful(
        call(
            "POST",
            "/users",
            Map.of("email", email(), "displayName", "Test User", "password", PASSWORD)),
        201);
  }

  private JsonNode named(String kind) throws Exception {
    return successful(call("POST", "/" + kind, Map.of("name", "R_" + unique())), 201);
  }

  private JsonNode permission(String action) throws Exception {
    return successful(
        call("POST", "/permissions", Map.of("resource", "r_" + unique(), "action", action)), 201);
  }

  @Test
  void protectsTheEntireNamespaceBeforeLookingUpUsers() throws Exception {
    JsonNode user = createUser();
    for (String path :
        List.of(
            "/users",
            "/users/" + user.path("id").asText(),
            "/users/" + UUID.randomUUID(),
            "/roles",
            "/groups",
            "/permissions")) {
      for (String credential : Arrays.asList(null, "incorrect-token")) {
        var response =
            client.send(
                request("GET", path, null, credential, "unauthenticated-it"),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(401, response.statusCode());
        assertEquals("Bearer", response.headers().firstValue("WWW-Authenticate").orElseThrow());
        assertEquals(
            "Administrative authentication is required.",
            json.readTree(response.body()).path("detail").asText());
        assertFalse(response.body().contains(user.path("email").asText()));
      }
    }
    long before = jdbc.queryForObject("SELECT count(*) FROM iam_identity.users", Long.class);
    var denied =
        client.send(
            request(
                "POST",
                "/users",
                Map.of("email", email(), "displayName", "Denied", "password", PASSWORD),
                null,
                "unauthenticated-it"),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(401, denied.statusCode());
    assertEquals(
        before, jdbc.queryForObject("SELECT count(*) FROM iam_identity.users", Long.class));
  }

  @Test
  void completesUserLifecycleAndNeverReturnsCredentials(CapturedOutput output) throws Exception {
    JsonNode user = createUser();
    String id = user.path("id").asText();
    JsonNode stored = successful(call("GET", "/users/" + id, null), 200);
    assertFalse(stored.has("password"));
    assertFalse(stored.has("passwordHash"));
    String oldHash =
        jdbc.queryForObject(
            "SELECT password_hash FROM iam_identity.credentials WHERE user_id=?::uuid",
            String.class,
            id);
    assertTrue(oldHash.startsWith("$argon2id$v=19$m=19456,t=2,p=1$"));
    assertTrue(Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8().matches(PASSWORD, oldHash));
    JsonNode updated =
        successful(
            call(
                "PUT",
                "/users/" + id,
                Map.of("email", email(), "displayName", "Updated", "version", 0)),
            200);
    assertEquals(1, updated.path("version").asLong());
    assertEquals(
        "LOCKED",
        successful(
                call("PATCH", "/users/" + id + "/status", Map.of("status", "LOCKED", "version", 1)),
                200)
            .path("status")
            .asText());
    successful(
        call("PATCH", "/users/" + id + "/status", Map.of("status", "ACTIVE", "version", 2)), 200);
    String newPassword = "Different-safe-password!";
    successful(
        call("PUT", "/users/" + id + "/password", Map.of("password", newPassword, "version", 3)),
        204);
    String newHash =
        jdbc.queryForObject(
            "SELECT password_hash FROM iam_identity.credentials WHERE user_id=?::uuid",
            String.class,
            id);
    assertNotEquals(oldHash, newHash);
    assertTrue(
        Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8().matches(newPassword, newHash));
    successful(call("DELETE", "/users/" + id + "?version=4", null), 204);
    JsonNode disabled = successful(call("GET", "/users/" + id, null), 200);
    assertEquals("DISABLED", disabled.path("status").asText());
    assertEquals(5, disabled.path("version").asLong());
    assertEquals(
        400,
        call("PATCH", "/users/" + id + "/status", Map.of("status", "ACTIVE", "version", 5))
            .statusCode());
    assertFalse(output.getAll().contains(PASSWORD));
    assertFalse(output.getAll().contains(newPassword));
    assertFalse(output.getAll().contains(TOKEN));
  }

  @Test
  void rejectsDuplicatesInvalidInputsAndStaleVersions() throws Exception {
    JsonNode user = createUser();
    String id = user.path("id").asText();
    successful(
        call(
            "POST",
            "/users",
            Map.of(
                "email",
                user.path("email").asText().toUpperCase(Locale.ROOT),
                "displayName",
                "Duplicate",
                "password",
                PASSWORD)),
        409);
    successful(
        call(
            "PUT", "/users/" + id, Map.of("email", email(), "displayName", "Stale", "version", 99)),
        409);
    successful(
        call(
            "POST",
            "/users",
            Map.of("email", "invalid", "displayName", "Name", "password", PASSWORD)),
        400);
    successful(
        call(
            "POST", "/users", Map.of("email", email(), "displayName", "Name", "password", "short")),
        400);
    successful(call("GET", "/users?limit=101", null), 400);
    successful(call("GET", "/users?offset=-1", null), 400);
    successful(call("GET", "/users/not-a-uuid", null), 400);
    successful(call("GET", "/users/" + UUID.randomUUID(), null), 404);
    successful(call("POST", "/roles", Map.of("name", "invalid role")), 400);
    successful(call("POST", "/permissions", Map.of("resource", "invoice", "action", "*")), 400);
    JsonNode role = named("roles");
    successful(call("POST", "/roles", Map.of("name", role.path("name").asText())), 409);
    JsonNode group = named("groups");
    successful(call("POST", "/groups", Map.of("name", group.path("name").asText())), 409);
    JsonNode permission = permission("read");
    successful(
        call(
            "POST",
            "/permissions",
            Map.of("resource", permission.path("resource").asText(), "action", "read")),
        409);
    successful(
        call(
            "PUT",
            "/roles/" + role.path("id").asText() + "/permissions/" + UUID.randomUUID(),
            null),
        404);
  }

  @Test
  void resolvesDirectAndGroupPermissionsAndRevokesInactiveUsers() throws Exception {
    JsonNode user = createUser(),
        role = named("roles"),
        group = named("groups"),
        permission = permission("read");
    String uid = user.path("id").asText(),
        rid = role.path("id").asText(),
        gid = group.path("id").asText(),
        pid = permission.path("id").asText();
    String userRole = "/users/" + uid + "/roles/" + rid,
        groupMember = "/groups/" + gid + "/members/" + uid,
        groupRole = "/groups/" + gid + "/roles/" + rid;
    successful(call("PUT", "/roles/" + rid + "/permissions/" + pid, null), 204);
    successful(call("PUT", userRole, null), 204);
    successful(call("PUT", userRole, null), 204);
    long assignments =
        jdbc.queryForObject(
            "SELECT count(*) FROM iam_audit.security_events WHERE resource=? AND event_type='access.assignment.granted' AND details->>'kind'='USER_ROLE'",
            Long.class,
            uid);
    assertEquals(1, assignments);
    successful(call("PUT", groupMember, null), 204);
    successful(call("PUT", groupRole, null), 204);
    JsonNode effective = successful(call("GET", "/users/" + uid + "/permissions", null), 200);
    assertEquals(1, effective.size());
    assertEquals(permission.path("key").asText(), effective.get(0).asText());
    successful(call("DELETE", userRole, null), 204);
    assertEquals(1, successful(call("GET", "/users/" + uid + "/permissions", null), 200).size());
    successful(call("DELETE", groupRole, null), 204);
    assertEquals(0, successful(call("GET", "/users/" + uid + "/permissions", null), 200).size());
    successful(call("PUT", groupRole, null), 204);
    successful(call("DELETE", groupMember, null), 204);
    assertEquals(0, successful(call("GET", "/users/" + uid + "/permissions", null), 200).size());
    successful(call("PUT", userRole, null), 204);
    successful(
        call("PATCH", "/users/" + uid + "/status", Map.of("status", "LOCKED", "version", 0)), 200);
    assertEquals(0, successful(call("GET", "/users/" + uid + "/permissions", null), 200).size());
    successful(call("PUT", groupMember, null), 400);
    successful(call("DELETE", userRole, null), 204);
    successful(
        call("PATCH", "/users/" + uid + "/status", Map.of("status", "ACTIVE", "version", 1)), 200);
    successful(call("PUT", userRole, null), 204);
    successful(call("DELETE", "/users/" + uid + "?version=2", null), 204);
    assertEquals(0, successful(call("GET", "/users/" + uid + "/permissions", null), 200).size());
    successful(call("DELETE", "/roles/" + rid + "/permissions/" + pid, null), 204);
  }

  @Test
  void listsAndReadsCatalogsWithBoundedPagination() throws Exception {
    JsonNode role = named("roles"), group = named("groups"), permission = permission("read");
    successful(call("GET", "/roles/" + role.path("id").asText(), null), 200);
    successful(call("GET", "/groups/" + group.path("id").asText(), null), 200);
    successful(call("GET", "/permissions/" + permission.path("id").asText(), null), 200);
    for (String path : List.of("/roles", "/groups", "/permissions", "/users")) {
      JsonNode page = successful(call("GET", path + "?limit=1&offset=0", null), 200);
      assertTrue(page.isArray());
      assertTrue(page.size() <= 1);
      successful(call("GET", path + "?limit=0", null), 400);
    }
    assertTrue(
        successful(call("GET", "/users/" + UUID.randomUUID() + "/permissions", null), 200)
            .isEmpty());
  }

  @Test
  void commitsAuditWithBusinessStateAndRejectsMutationsOfAuditRows() throws Exception {
    JsonNode user = createUser();
    String id = user.path("id").asText();
    var event =
        jdbc.queryForMap(
            "SELECT id,actor,correlation_id,details::text FROM iam_audit.security_events WHERE resource=? AND event_type='identity.user.created'",
            id);
    assertEquals("bootstrap-admin", event.get("actor"));
    assertEquals("admin-it", event.get("correlation_id"));
    assertFalse(event.get("details").toString().contains(PASSWORD));
    assertFalse(event.get("details").toString().contains(user.path("email").asText()));
    assertThrows(
        DataAccessException.class,
        () ->
            jdbc.update(
                "UPDATE iam_audit.security_events SET actor='tampered' WHERE id=?",
                event.get("id")));
    assertThrows(
        DataAccessException.class,
        () -> jdbc.update("DELETE FROM iam_audit.security_events WHERE id=?", event.get("id")));
    assertThrows(
        DataAccessException.class, () -> jdbc.execute("TRUNCATE iam_audit.security_events"));
    assertEquals(
        "bootstrap-admin",
        jdbc.queryForObject(
            "SELECT actor FROM iam_audit.security_events WHERE id=?",
            String.class,
            event.get("id")));
  }

  @Test
  void rollsBackUserAndCredentialWhenAuditFails() throws Exception {
    doThrow(new IllegalStateException("synthetic audit outage"))
        .when(audit)
        .record(
            argThat(
                event -> event != null && event.context().correlationId().equals("fail-audit")));
    String email = email();
    var response =
        client.send(
            request(
                "POST",
                "/users",
                Map.of("email", email, "displayName", "Rollback", "password", PASSWORD),
                TOKEN,
                "fail-audit"),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(500, response.statusCode());
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM iam_identity.users WHERE email=?", Integer.class, email));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM iam_audit.security_events WHERE correlation_id='fail-audit'",
            Integer.class));
  }

  @Test
  void rollsBackPermissionAssignmentWhenAuditFails() throws Exception {
    JsonNode role = named("roles"), permission = permission("read");
    String rid = role.path("id").asText(), pid = permission.path("id").asText();
    doThrow(new IllegalStateException("synthetic audit outage"))
        .when(audit)
        .record(
            argThat(
                event ->
                    event != null && event.context().correlationId().equals("fail-access-audit")));
    var response =
        client.send(
            request(
                "PUT", "/roles/" + rid + "/permissions/" + pid, null, TOKEN, "fail-access-audit"),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(500, response.statusCode());
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM iam_access.role_permissions WHERE role_id=?::uuid AND permission_id=?::uuid",
            Integer.class,
            rid,
            pid));
  }

  @Test
  void concurrentRegistrationsCannotCreateDuplicateCredentialsOrAudit() throws Exception {
    String email = email();
    var body = Map.of("email", email, "displayName", "Concurrent", "password", PASSWORD);
    var first =
        client.sendAsync(
            request("POST", "/users", body, TOKEN, "create-a"),
            HttpResponse.BodyHandlers.ofString());
    var second =
        client.sendAsync(
            request("POST", "/users", body, TOKEN, "create-b"),
            HttpResponse.BodyHandlers.ofString());
    CompletableFuture.allOf(first, second).join();
    assertEquals(Set.of(201, 409), Set.of(first.join().statusCode(), second.join().statusCode()));
    UUID userId =
        jdbc.queryForObject("SELECT id FROM iam_identity.users WHERE email=?", UUID.class, email);
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM iam_identity.credentials WHERE user_id=?",
            Integer.class,
            userId));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM iam_audit.security_events WHERE resource=? AND event_type='identity.user.created'",
            Integer.class,
            userId.toString()));
  }

  @Test
  void preventsLostUpdatesInConcurrentRequests() throws Exception {
    JsonNode user = createUser();
    String id = user.path("id").asText();
    var first =
        client.sendAsync(
            request(
                "PUT",
                "/users/" + id,
                Map.of("email", email(), "displayName", "First", "version", 0),
                TOKEN,
                "concurrent-a"),
            HttpResponse.BodyHandlers.ofString());
    var second =
        client.sendAsync(
            request(
                "PUT",
                "/users/" + id,
                Map.of("email", email(), "displayName", "Second", "version", 0),
                TOKEN,
                "concurrent-b"),
            HttpResponse.BodyHandlers.ofString());
    CompletableFuture.allOf(first, second).join();
    assertEquals(Set.of(200, 409), Set.of(first.join().statusCode(), second.join().statusCode()));
    assertEquals(1, successful(call("GET", "/users/" + id, null), 200).path("version").asLong());
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM iam_audit.security_events WHERE resource=? AND event_type='identity.user.updated'",
            Integer.class,
            id));
  }

  @Test
  void repositoryAlsoRejectsStaleWritesInsideAnExistingTransaction() throws Exception {
    JsonNode created = createUser();
    UUID id = UUID.fromString(created.path("id").asText());
    User user = users.find(id).orElseThrow();
    users.update(user.profile(user.email().value(), "First"), 0);
    assertThrows(
        ConcurrentUpdateException.class,
        () -> users.update(user.profile(user.email().value(), "Second"), 0));
  }
}

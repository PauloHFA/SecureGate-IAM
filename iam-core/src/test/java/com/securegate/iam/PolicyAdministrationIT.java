package com.securegate.iam;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securegate.iam.audit.application.port.AuditTrail;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PolicyAdministrationIT {
  private static final String TOKEN = UUID.randomUUID().toString() + UUID.randomUUID();
  private static final HttpClient HTTP = HttpClient.newHttpClient();

  @Container
  static final PostgreSQLContainer<?> DATABASE =
      new PostgreSQLContainer<>("postgres:16.15-alpine")
          .withDatabaseName("policy_test")
          .withUsername("policy_test")
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
  @MockitoSpyBean AuditTrail audit;

  private String unique() {
    return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
  }

  private String target() {
    return "invoices-" + unique();
  }

  private HttpResponse<String> call(String method, String path, Object body) throws Exception {
    return call(method, path, body, TOKEN, "policy-it");
  }

  private HttpResponse<String> call(
      String method, String path, Object body, String token, String correlation) throws Exception {
    var builder =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/admin" + path))
            .header("X-Correlation-ID", correlation);
    if (token != null) {
      builder.header("Authorization", "Bearer " + token);
    }
    if (body != null) {
      builder.header("Content-Type", "application/json");
    }
    return HTTP.send(
        builder
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private JsonNode success(HttpResponse<String> response, int status) throws Exception {
    assertEquals(status, response.statusCode(), response.body());
    return json.readTree(response.body());
  }

  private Map<String, Object> document(
      String resource, String effect, Set<String> roles, Set<String> scopes, Integer ttl) {
    Map<String, Object> document = new HashMap<>();
    document.put("schemaVersion", 1);
    document.put("resource", resource);
    document.put("action", "read");
    document.put("effect", effect);
    document.put("rolesAnyOf", roles);
    document.put("allowedScopes", scopes);
    document.put("maxAccessTokenTtlSeconds", ttl);
    return document;
  }

  private Map<String, Object> body(
      String resource,
      String effect,
      int priority,
      boolean enabled,
      Set<String> roles,
      Set<String> scopes,
      Integer ttl) {
    return new HashMap<>(
        Map.of(
            "name",
            "policy-" + unique(),
            "priority",
            priority,
            "enabled",
            enabled,
            "document",
            document(resource, effect, roles, scopes, ttl)));
  }

  private JsonNode create(
      String resource,
      String effect,
      int priority,
      boolean enabled,
      Set<String> roles,
      Set<String> scopes,
      Integer ttl)
      throws Exception {
    return success(
        call("POST", "/policies", body(resource, effect, priority, enabled, roles, scopes, ttl)),
        201);
  }

  private UUID user() throws Exception {
    var created =
        success(
            call(
                "POST",
                "/users",
                Map.of(
                    "email",
                    "policy-" + unique() + "@example.test",
                    "displayName",
                    "Policy Test",
                    "password",
                    "Synthetic-policy-password-123!")),
            201);
    return UUID.fromString(created.path("id").asText());
  }

  private JsonNode evaluate(UUID user, String resource) throws Exception {
    return success(
        call(
            "POST",
            "/policies/evaluate",
            Map.of(
                "userId",
                user,
                "resource",
                resource,
                "action",
                "read",
                "requestedScopes",
                Set.of("api:read", "api:write"))),
        200);
  }

  private Map<String, Object> updateBody(JsonNode policy) {
    return new HashMap<>(
        Map.of(
            "name",
            policy.path("name").asText(),
            "priority",
            policy.path("priority").asInt(),
            "enabled",
            policy.path("enabled").asBoolean(),
            "version",
            policy.path("version").asLong(),
            "document",
            policy.path("document")));
  }

  @Test
  void persistsJsonbListsReadsUpdatesAndAuditsPolicyVersions() throws Exception {
    String resource = target();
    var body = body(resource, "ALLOW", 25, false, Set.of(), Set.of(), null);
    var response = call("POST", "/policies", body);
    var created = success(response, 201);
    String id = created.path("id").asText();
    assertEquals(
        "/api/admin/policies/" + id, response.headers().firstValue("Location").orElseThrow());
    assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
    assertEquals(0, created.path("version").asLong());
    assertTrue(created.path("document").isObject());
    assertEquals(
        "object",
        jdbc.queryForObject(
            "SELECT jsonb_typeof(document) FROM iam_policy.policies WHERE id=?",
            String.class,
            UUID.fromString(id)));
    assertEquals(created, success(call("GET", "/policies/" + id, null), 200));
    var list = success(call("GET", "/policies?limit=100", null), 200);
    assertTrue(
        java.util.stream.StreamSupport.stream(list.spliterator(), false)
            .anyMatch(policy -> id.equals(policy.path("id").asText())));
    var replacement = updateBody(created);
    replacement.put("enabled", true);
    var updated = success(call("PUT", "/policies/" + id, replacement), 200);
    assertTrue(updated.path("enabled").asBoolean());
    assertEquals(1, updated.path("version").asLong());
    var events =
        jdbc.queryForList(
            "SELECT event_type,details::text,correlation_id FROM iam_audit.security_events WHERE resource=? ORDER BY occurred_at",
            id);
    assertEquals(2, events.size());
    assertEquals("policy.created", events.getFirst().get("event_type"));
    assertEquals("policy.updated", events.getLast().get("event_type"));
    assertEquals("policy-it", events.getLast().get("correlation_id"));
    assertFalse(events.toString().contains(resource));
    assertEquals(409, call("POST", "/policies", body).statusCode());
    assertEquals(404, call("GET", "/policies/" + UUID.randomUUID(), null).statusCode());
    assertEquals(404, call("PUT", "/policies/" + UUID.randomUUID(), replacement).statusCode());
    assertEquals(400, call("GET", "/policies?limit=101", null).statusCode());
  }

  @Test
  void evaluatesCurrentDirectAndInheritedRolesAndDeniesInactiveIdentities() throws Exception {
    UUID user = user();
    String resource = target();
    String roleName = "READER_" + unique().toUpperCase(java.util.Locale.ROOT);
    create(resource, "ALLOW", 10, true, Set.of(roleName), Set.of(), null);
    assertEquals("DENY", evaluate(user, resource).path("effect").asText());
    var role = success(call("POST", "/roles", Map.of("name", roleName)), 201);
    var group =
        success(
            call(
                "POST",
                "/groups",
                Map.of("name", "TEAM_" + unique().toUpperCase(java.util.Locale.ROOT))),
            201);
    String roleId = role.path("id").asText();
    String groupId = group.path("id").asText();
    successNoBody(call("PUT", "/groups/" + groupId + "/members/" + user, null));
    successNoBody(call("PUT", "/groups/" + groupId + "/roles/" + roleId, null));
    assertEquals("ALLOW", evaluate(user, resource).path("effect").asText());
    successNoBody(call("PUT", "/users/" + user + "/roles/" + roleId, null));
    successNoBody(call("DELETE", "/groups/" + groupId + "/roles/" + roleId, null));
    assertEquals("ALLOW", evaluate(user, resource).path("effect").asText());
    successNoBody(call("DELETE", "/users/" + user + "/roles/" + roleId, null));
    assertEquals("DENY", evaluate(user, resource).path("effect").asText());
    jdbc.update("UPDATE iam_identity.users SET status='LOCKED',version=version+1 WHERE id=?", user);
    assertEquals("IDENTITY_INACTIVE", evaluate(user, resource).path("reasons").get(0).asText());
    assertEquals(
        "IDENTITY_INACTIVE", evaluate(UUID.randomUUID(), resource).path("reasons").get(0).asText());
  }

  private void successNoBody(HttpResponse<String> response) {
    assertEquals(204, response.statusCode(), response.body());
  }

  @Test
  void combinesMfaAndLimitsAndExplicitDenyWithoutGrantingAccess() throws Exception {
    UUID user = user();
    String resource = target();
    var disabled = create(resource, "DENY", 1000, false, Set.of(), Set.of(), null);
    create(resource, "ALLOW", 1000, true, Set.of(), Set.of(), null);
    create(resource, "ALLOW_LIMITED", 10, true, Set.of(), Set.of("api:read"), 90);
    create(resource, "ALLOW_WITH_MFA", 1, true, Set.of(), Set.of(), null);
    var result = evaluate(user, resource);
    assertEquals("ALLOW_WITH_MFA", result.path("effect").asText());
    assertTrue(result.path("mfaRequired").asBoolean());
    assertEquals(90, result.path("maxAccessTokenTtlSeconds").asInt());
    assertEquals(List.of("api:read"), json.convertValue(result.path("allowedScopes"), List.class));
    assertEquals(3, result.path("appliedPolicies").size());
    var replacement = updateBody(disabled);
    replacement.put("enabled", true);
    replacement.put("priority", 0);
    success(call("PUT", "/policies/" + disabled.path("id").asText(), replacement), 200);
    var denied = evaluate(user, resource);
    assertEquals("DENY", denied.path("effect").asText());
    assertTrue(denied.path("allowedScopes").isEmpty());
    assertEquals(0, denied.path("maxAccessTokenTtlSeconds").asInt());
  }

  @Test
  void concurrentUpdatesHaveOneWinnerAndNoLostVersionOrAudit() throws Exception {
    var original = create(target(), "ALLOW", 10, true, Set.of(), Set.of(), null);
    var replacement = updateBody(original);
    replacement.put("enabled", false);
    String path = "/policies/" + original.path("id").asText();
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var first = executor.submit(() -> call("PUT", path, replacement).statusCode());
      var second = executor.submit(() -> call("PUT", path, replacement).statusCode());
      assertEquals(Set.of(200, 409), Set.of(first.get(), second.get()));
    }
    assertEquals(1, success(call("GET", path, null), 200).path("version").asLong());
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM iam_audit.security_events WHERE resource=? AND event_type='policy.updated'",
            Integer.class,
            original.path("id").asText()));
  }

  @Test
  void auditFailureRollsBackPolicyCreationAndUpdates() throws Exception {
    var original = create(target(), "ALLOW", 10, true, Set.of(), Set.of(), null);
    doThrow(new IllegalStateException("Synthetic audit failure"))
        .when(audit)
        .record(argThat(event -> event.context().correlationId().equals("rollback-policy")));
    var body = body(target(), "DENY", 10, true, Set.of(), Set.of(), null);
    assertEquals(500, call("POST", "/policies", body, TOKEN, "rollback-policy").statusCode());
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM iam_policy.policies WHERE name=?",
            Integer.class,
            body.get("name")));
    var replacement = updateBody(original);
    replacement.put("enabled", false);
    assertEquals(
        500,
        call(
                "PUT",
                "/policies/" + original.path("id").asText(),
                replacement,
                TOKEN,
                "rollback-policy")
            .statusCode());
    assertEquals(
        original, success(call("GET", "/policies/" + original.path("id").asText(), null), 200));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"unknown", "expression", "effect", "schema", "ttl", "roles", "coercion", "scopes"})
  void rejectsUnsupportedOrMalformedPolicyDocumentsWithoutEchoingThem(String scenario)
      throws Exception {
    var document = document(target(), "ALLOW", Set.of(), Set.of(), null);
    switch (scenario) {
      case "unknown" -> document.put("arbitrary", true);
      case "expression" -> document.put("expression", "T(java.lang.Runtime).getRuntime()");
      case "effect" -> document.put("effect", "PERMIT");
      case "schema" -> document.put("schemaVersion", 2);
      case "ttl" -> document.put("maxAccessTokenTtlSeconds", 60);
      case "roles" -> document.put("rolesAnyOf", Set.of("reader"));
      case "coercion" -> document.put("schemaVersion", "1");
      case "scopes" -> document.put("allowedScopes", Set.of("api:read"));
    }
    var response =
        call(
            "POST",
            "/policies",
            Map.of(
                "name",
                "invalid-" + unique(),
                "priority",
                1,
                "enabled",
                true,
                "document",
                document));
    assertEquals(400, response.statusCode(), response.body());
    assertFalse(response.body().contains("java.lang.Runtime"));
    assertTrue(
        response
            .headers()
            .firstValue("Content-Type")
            .orElse("")
            .contains("application/problem+json"));
  }

  @Test
  void invalidStoredDocumentFailsClosedRatherThanSkippingTheRule() throws Exception {
    String resource = target();
    UUID user = user();
    var policy = create(resource, "ALLOW", 1, true, Set.of(), Set.of(), null);
    jdbc.update(
        "UPDATE iam_policy.policies SET document=jsonb_set(document,'{effect}','\"UNSUPPORTED\"'::jsonb) WHERE id=?",
        UUID.fromString(policy.path("id").asText()));
    var response =
        call(
            "POST",
            "/policies/evaluate",
            Map.of(
                "userId",
                user,
                "resource",
                resource,
                "action",
                "read",
                "requestedScopes",
                Set.of("api:read")));
    assertEquals(500, response.statusCode());
    assertFalse(response.body().contains("UNSUPPORTED"));
  }

  @Test
  void policyEndpointsRequireBootstrapAndRejectInvalidPreviewRequests() throws Exception {
    for (String path : List.of("/policies", "/policies/" + UUID.randomUUID())) {
      assertEquals(401, call("GET", path, null, null, "anonymous-policy").statusCode());
      assertEquals(
          401, call("GET", path, null, "incorrect-token", "anonymous-policy").statusCode());
    }
    assertEquals(
        401, call("POST", "/policies/evaluate", Map.of(), null, "anonymous-policy").statusCode());
    assertEquals(400, call("POST", "/policies/evaluate", Map.of()).statusCode());
    assertEquals(
        400,
        call(
                "POST",
                "/policies/evaluate",
                Map.of(
                    "userId",
                    UUID.randomUUID(),
                    "resource",
                    "*",
                    "action",
                    "read",
                    "requestedScopes",
                    Set.of("api:read")))
            .statusCode());
    assertEquals(
        400,
        call(
                "POST",
                "/policies",
                Map.of("name", "test-policy", "priority", 1, "document", Map.of()))
            .statusCode());
  }
}

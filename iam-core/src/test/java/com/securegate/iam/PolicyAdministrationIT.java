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
  @Test
  void canonicalPolicyChangeCarriesTargetAndVersionAndFailureRollsBackBothEvents()
      throws Exception {
    String resource = target();
    var created = create(resource, "ALLOW", 100, true, Set.of(), Set.of(), null);
    String id = created.path("id").asText();
    var payload =
        jdbc.queryForObject(
            "SELECT payload::text FROM iam_audit.outbox_events WHERE event_type='policy.changed' AND payload->'data'->>'policyId'=?",
            String.class,
            id);
    var fact = json.readValue(payload, com.securegate.events.SecurityEvent.class);
    assertEquals(resource, fact.data().get("resource"));
    assertEquals("CREATED", fact.data().get("operation"));
    assertEquals("0", fact.data().get("version"));
    doThrow(new IllegalStateException("synthetic canonical policy failure"))
        .when(audit)
        .record(argThat(event -> event.type().value().equals("policy.changed")));
    var replacement = updateBody(created);
    replacement.put("enabled", false);
    assertEquals(500, call("PUT", "/policies/" + id, replacement).statusCode());
    assertEquals(
        0L,
        jdbc.queryForObject(
            "SELECT version FROM iam_policy.policies WHERE id=?::uuid", Long.class, id));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM iam_audit.outbox_events WHERE event_type='policy.changed' AND payload->'data'->>'policyId'=?",
            Integer.class,
            id));
    org.mockito.Mockito.reset(audit);
    var updated = success(call("PUT", "/policies/" + id, replacement), 200);
    assertEquals(1, updated.path("version").asInt());
    assertEquals(
        2,
        jdbc.queryForObject(
            "SELECT count(*) FROM iam_audit.outbox_events WHERE event_type='policy.changed' AND payload->'data'->>'policyId'=?",
            Integer.class,
            id));
  }

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
  @Autowired com.securegate.iam.policy.application.port.RiskEvidence evidence;
  @MockitoSpyBean java.time.Clock clock;

  private String unique() {
    return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
  }

  @Test
  void administersClassificationsAndNetworksWithVersionsAndAuditRollback() throws Exception {
    String resource = target();
    var created =
        success(
            call(
                "POST",
                "/risk/resources",
                Map.of("resource", resource, "sensitivity", "CONFIDENTIAL")),
            201);
    assertEquals(0, created.path("version").asLong());
    assertEquals(created, success(call("GET", "/risk/resources?resource=" + resource, null), 200));
    var updated =
        success(
            call(
                "PUT",
                "/risk/resources",
                Map.of("resource", resource, "sensitivity", "PUBLIC", "version", 0)),
            200);
    assertEquals(1, updated.path("version").asLong());
    assertEquals(
        409,
        call(
                "PUT",
                "/risk/resources",
                Map.of("resource", resource, "sensitivity", "RESTRICTED", "version", 0))
            .statusCode());
    assertEquals(
        409,
        call("POST", "/risk/resources", Map.of("resource", resource, "sensitivity", "PUBLIC"))
            .statusCode());
    assertEquals(
        400,
        call("POST", "/risk/resources", Map.of("resource", target(), "sensitivity", "UNKNOWN"))
            .statusCode());
    assertEquals(404, call("GET", "/risk/resources?resource=" + target(), null).statusCode());
    var network = success(call("POST", "/risk/networks", Map.of("cidr", "192.0.2.1/24")), 201);
    assertEquals("192.0.2.0/24", network.path("cidr").asText());
    assertEquals(409, call("POST", "/risk/networks", Map.of("cidr", "192.0.2.9/24")).statusCode());
    success(call("GET", "/risk/networks", null), 200);
    var disabled =
        success(
            call(
                "PUT",
                "/risk/networks/" + network.path("id").asText(),
                Map.of("enabled", false, "version", 0)),
            200);
    assertFalse(disabled.path("enabled").asBoolean());
    assertEquals(
        409,
        call(
                "PUT",
                "/risk/networks/" + network.path("id").asText(),
                Map.of("enabled", true, "version", 0))
            .statusCode());
    assertEquals(
        400, call("POST", "/risk/networks", Map.of("cidr", "example.com/24")).statusCode());
    assertEquals(401, call("GET", "/risk/networks", null, null, "anonymous-risk").statusCode());
    doThrow(new IllegalStateException("synthetic"))
        .when(audit)
        .record(argThat(event -> event.type().value().equals("risk.resource.updated")));
    assertEquals(
        500,
        call(
                "PUT",
                "/risk/resources",
                Map.of("resource", resource, "sensitivity", "RESTRICTED", "version", 1))
            .statusCode());
    assertEquals(updated, success(call("GET", "/risk/resources?resource=" + resource, null), 200));
  }

  @Test
  void bindsContextToIdentityVersionAndExpiresEvidenceWithoutTreatingItAsSafe() throws Exception {
    var now = java.time.Instant.now().plusSeconds(600);
    org.mockito.Mockito.doReturn(now).when(clock).instant();
    UUID user = user();
    UUID other = user();
    UUID context = UUID.randomUUID();
    String resource = target();
    success(
        call("POST", "/risk/resources", Map.of("resource", resource, "sensitivity", "PUBLIC")),
        201);
    UUID device = evidence.observe(user, 0, context, "not-a-server-device", "203.0.113.5");
    var observed = evidence.collect(user, resource, context);
    assertEquals(
        com.securegate.iam.policy.domain.RiskSignals.Trust.UNTRUSTED, observed.deviceTrust());
    assertNull(observed.failedLoginAttempts()); // Authorization is disabled in this fixture.
    assertEquals(
        com.securegate.iam.policy.domain.RiskSignals.Sensitivity.PUBLIC,
        observed.resourceSensitivity());
    assertEquals(
        com.securegate.iam.policy.domain.RiskSignals.Trust.UNKNOWN,
        evidence.collect(other, resource, context).deviceTrust());
    assertThrows(
        IllegalStateException.class,
        () -> evidence.observe(other, 0, context, null, "203.0.113.5"));
    var approved =
        success(
            call(
                "PUT",
                "/risk/users/" + user + "/devices/" + device,
                Map.of("trusted", true, "ttlSeconds", 600, "version", 0)),
            200);
    assertEquals(1, approved.path("version").asLong());
    success(
        call(
            "PUT",
            "/risk/users/" + user + "/devices/" + device,
            Map.of("trusted", false, "ttlSeconds", 600, "version", 1)),
        200);
    assertEquals(
        com.securegate.iam.policy.domain.RiskSignals.Trust.UNTRUSTED,
        evidence.collect(user, resource, context).deviceTrust());
    success(
        call(
            "PUT",
            "/risk/users/" + user + "/devices/" + device,
            Map.of("trusted", true, "ttlSeconds", 600, "version", 2)),
        200);
    var network = success(call("POST", "/risk/networks", Map.of("cidr", "203.0.113.0/24")), 201);
    assertEquals(
        com.securegate.iam.policy.domain.RiskSignals.Trust.TRUSTED,
        evidence.collect(user, resource, context).networkTrust());
    success(
        call(
            "PUT",
            "/risk/networks/" + network.path("id").asText(),
            Map.of("enabled", false, "version", 0)),
        200);
    assertEquals(
        com.securegate.iam.policy.domain.RiskSignals.Trust.UNTRUSTED,
        evidence.collect(user, resource, context).networkTrust());
    assertEquals(
        com.securegate.iam.policy.domain.RiskSignals.Trust.TRUSTED,
        evidence.collect(user, resource, context).deviceTrust());
    assertEquals(
        404, call("GET", "/risk/users/" + other + "/devices/" + device, null).statusCode());
    assertEquals(
        409,
        call(
                "PUT",
                "/risk/users/" + user + "/devices/" + device,
                Map.of("trusted", false, "ttlSeconds", 600, "version", 0))
            .statusCode());
    org.mockito.Mockito.doReturn(now.plusSeconds(301)).when(clock).instant();
    assertEquals(
        com.securegate.iam.policy.domain.RiskSignals.Trust.UNKNOWN,
        evidence.collect(user, resource, context).deviceTrust());
    assertTrue(evidence.contexts(user).isEmpty());
    evidence.observe(user, 0, context, device.toString(), "203.0.113.5");
    assertEquals(
        com.securegate.iam.policy.domain.RiskSignals.Trust.TRUSTED,
        evidence.collect(user, resource, context).deviceTrust());
    org.mockito.Mockito.doReturn(now.plusSeconds(601)).when(clock).instant();
    evidence.observe(user, 0, context, device.toString(), "203.0.113.5");
    assertEquals(
        com.securegate.iam.policy.domain.RiskSignals.Trust.UNTRUSTED,
        evidence.collect(user, resource, context).deviceTrust());
    jdbc.update("UPDATE iam_identity.users SET version=version+1 WHERE id=?", user);
    assertEquals(
        com.securegate.iam.policy.domain.RiskSignals.Trust.UNKNOWN,
        evidence.collect(user, resource, context).deviceTrust());
    assertThrows(
        IllegalArgumentException.class,
        () -> evidence.observe(user, 0, UUID.randomUUID(), device.toString(), "203.0.113.5"));
    evidence.close(user, context);
    assertThrows(
        IllegalStateException.class,
        () -> evidence.observe(user, 1, context, device.toString(), "203.0.113.5"));
    UUID freshContext = UUID.randomUUID();
    evidence.observe(user, 1, freshContext, device.toString(), "203.0.113.5");
    evidence.close(user, freshContext);
    assertThrows(
        IllegalStateException.class,
        () -> evidence.observe(user, 1, freshContext, device.toString(), "203.0.113.5"));
    assertEquals(
        404, call("GET", "/risk/users/" + UUID.randomUUID() + "/contexts", null).statusCode());
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
            "SELECT event_type,details::text,correlation_id FROM iam_audit.security_events WHERE resource=? AND event_type IN ('policy.created','policy.updated') ORDER BY occurred_at",
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

  private Map<String, Object> condition(String attribute, String operator, String... values) {
    return Map.of("attribute", attribute, "operator", operator, "values", List.of(values));
  }

  private JsonNode abac(String resource, String effect, List<Map<String, Object>> conditions)
      throws Exception {
    var body =
        body(
            resource,
            effect,
            10,
            true,
            Set.of(),
            effect.equals("ALLOW_LIMITED") ? Set.of("api:read") : Set.of(),
            effect.equals("ALLOW_LIMITED") ? 90 : null);
    @SuppressWarnings("unchecked")
    var document = (Map<String, Object>) body.get("document");
    document.put("schemaVersion", 2);
    document.put("conditions", conditions);
    return success(call("POST", "/policies", body), 201);
  }

  private JsonNode simulate(UUID user, String resource, Map<String, Object> scenario)
      throws Exception {
    return success(
        call(
            "POST",
            "/policies/simulate",
            Map.of(
                "userId",
                user,
                "resource",
                resource,
                "action",
                "read",
                "requestedScopes",
                Set.of("api:read", "api:write"),
                "scenario",
                scenario)),
        200);
  }

  private Map<String, Object> safeScenario() {
    return Map.of(
        "deviceTrust",
        "TRUSTED",
        "networkTrust",
        "TRUSTED",
        "failedLoginAttempts",
        0,
        "resourceSensitivity",
        "PUBLIC");
  }

  @Test
  void simulatesRiskBasedAllowMfaAndDenyWithoutAcceptingSelfAttestedFacts() throws Exception {
    UUID user = user();
    String resource = target();
    abac(
        resource,
        "ALLOW",
        List.of(condition("DEVICE_TRUST", "EQ", "TRUSTED"), condition("RISK_SCORE", "LTE", "29")));
    abac(
        resource,
        "ALLOW_WITH_MFA",
        List.of(condition("RISK_SCORE", "GTE", "30"), condition("RISK_SCORE", "LTE", "79")));
    abac(resource, "DENY", List.of(condition("RISK_SCORE", "GTE", "80")));
    var low = simulate(user, resource, safeScenario());
    assertTrue(low.path("simulation").asBoolean());
    assertEquals(0, low.path("risk").path("score").asInt());
    assertEquals("ALLOW", low.path("effect").asText());
    var medium =
        simulate(
            user,
            resource,
            Map.of(
                "deviceTrust",
                "UNTRUSTED",
                "networkTrust",
                "UNTRUSTED",
                "failedLoginAttempts",
                1,
                "resourceSensitivity",
                "PUBLIC"));
    assertEquals(55, medium.path("risk").path("score").asInt());
    assertEquals("MEDIUM", medium.path("risk").path("level").asText());
    assertEquals("ALLOW_WITH_MFA", medium.path("effect").asText());
    assertTrue(medium.path("mfaRequired").asBoolean());
    var high =
        simulate(
            user,
            resource,
            Map.of(
                "deviceTrust",
                "UNTRUSTED",
                "networkTrust",
                "UNTRUSTED",
                "failedLoginAttempts",
                5,
                "resourceSensitivity",
                "RESTRICTED"));
    assertEquals(100, high.path("risk").path("score").asInt());
    assertEquals("DENY", high.path("effect").asText());
    var actual =
        success(
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
                    Set.of("api:read"),
                    "scenario",
                    safeScenario(),
                    "riskScore",
                    0,
                    "deviceTrust",
                    "TRUSTED")),
            200);
    assertFalse(actual.path("simulation").asBoolean());
    assertEquals(100, actual.path("risk").path("score").asInt());
    assertEquals(4, actual.path("risk").path("missingSignals").size());
    assertEquals("DENY", actual.path("effect").asText());
    assertEquals(100, evaluate(user, resource).path("risk").path("score").asInt());
  }

  @Test
  void missingDenyAttributeCannotBeIgnoredAndDisablingItRestoresLegacyBehavior() throws Exception {
    UUID user = user();
    String resource = target();
    create(resource, "ALLOW", 1000, true, Set.of(), Set.of(), null);
    var conditional = abac(resource, "DENY", List.of(condition("DEVICE_TRUST", "EQ", "UNTRUSTED")));
    var actual = evaluate(user, resource);
    assertEquals("DENY", actual.path("effect").asText());
    assertEquals("INDETERMINATE_ATTRIBUTES", actual.path("reasons").get(0).asText());
    assertEquals(2, actual.path("appliedPolicies").size());
    assertEquals("ALLOW", simulate(user, resource, safeScenario()).path("effect").asText());
    assertEquals("DENY", simulate(user, resource, Map.of()).path("effect").asText());
    var replacement = updateBody(conditional);
    replacement.put("enabled", false);
    success(call("PUT", "/policies/" + conditional.path("id").asText(), replacement), 200);
    var legacy = evaluate(user, resource);
    assertEquals("ALLOW", legacy.path("effect").asText());
    assertEquals(100, legacy.path("risk").path("score").asInt());
    assertFalse(legacy.path("simulation").asBoolean());
  }

  @Test
  void combinesAbacLimitsWithMfaAndUpdatesLegacyDocumentsWithoutImplicitMigration()
      throws Exception {
    UUID user = user();
    String resource = target();
    var legacy = create(resource, "ALLOW", 100, true, Set.of(), Set.of(), null);
    assertFalse(legacy.path("document").has("conditions"));
    var limited =
        abac(
            resource,
            "ALLOW_LIMITED",
            List.of(condition("RESOURCE_SENSITIVITY", "IN", "CONFIDENTIAL", "RESTRICTED")));
    abac(resource, "ALLOW_WITH_MFA", List.of(condition("RISK_SCORE", "GTE", "30")));
    var result =
        simulate(
            user,
            resource,
            Map.of(
                "deviceTrust",
                "UNTRUSTED",
                "networkTrust",
                "TRUSTED",
                "failedLoginAttempts",
                0,
                "resourceSensitivity",
                "CONFIDENTIAL"));
    assertEquals("ALLOW_WITH_MFA", result.path("effect").asText());
    assertEquals(40, result.path("risk").path("score").asInt());
    assertEquals(90, result.path("maxAccessTokenTtlSeconds").asInt());
    assertEquals(List.of("api:read"), json.convertValue(result.path("allowedScopes"), List.class));
    assertEquals(2, limited.path("document").path("schemaVersion").asInt());
    assertEquals(
        limited, success(call("GET", "/policies/" + limited.path("id").asText(), null), 200));
    var replacement = updateBody(legacy);
    replacement.put("document", limited.path("document"));
    var updated = success(call("PUT", "/policies/" + legacy.path("id").asText(), replacement), 200);
    assertEquals(1, updated.path("version").asLong());
    assertEquals(2, updated.path("document").path("schemaVersion").asInt());
  }

  @ParameterizedTest
  @ValueSource(strings = {"attribute", "operator", "script", "v1", "number", "missing"})
  void rejectsMalformedAbacRulesAtHttpBoundary(String invalid) throws Exception {
    var body = body(target(), "ALLOW", 1, true, Set.of(), Set.of(), null);
    @SuppressWarnings("unchecked")
    var document = (Map<String, Object>) body.get("document");
    var condition = new HashMap<>(condition("RISK_SCORE", "LTE", "29"));
    document.put("schemaVersion", 2);
    document.put("conditions", List.of(condition));
    switch (invalid) {
      case "attribute" -> condition.put("attribute", "request.headers.authorization");
      case "operator" -> condition.put("operator", "SCRIPT");
      case "script" -> condition.put("expression", "T(java.lang.Runtime).getRuntime()");
      case "v1" -> document.put("schemaVersion", 1);
      case "number" -> condition.put("values", List.of(29));
      default -> document.remove("conditions");
    }
    var response = call("POST", "/policies", body);
    assertEquals(400, response.statusCode(), response.body());
    assertFalse(response.body().contains("java.lang.Runtime"));
  }

  @Test
  void validatesSimulationAndKeepsItBehindBootstrap() throws Exception {
    var request =
        new HashMap<String, Object>(
            Map.of(
                "userId",
                user(),
                "resource",
                target(),
                "action",
                "read",
                "requestedScopes",
                Set.of("api:read"),
                "scenario",
                safeScenario()));
    assertEquals(
        401, call("POST", "/policies/simulate", request, null, "anonymous-risk").statusCode());
    request.put("scenario", Map.of("failedLoginAttempts", -1));
    assertEquals(400, call("POST", "/policies/simulate", request).statusCode());
    request.put("scenario", Map.of("deviceTrust", "trusted"));
    assertEquals(400, call("POST", "/policies/simulate", request).statusCode());
    request.remove("scenario");
    assertEquals(400, call("POST", "/policies/simulate", request).statusCode());
  }

  @Test
  void upgradesV5DatabaseWithExistingV1PolicyBeforeAcceptingV2() throws Exception {
    String database = "upgrade_" + UUID.randomUUID().toString().replace("-", "");
    jdbc.execute("CREATE DATABASE " + database);
    String url = DATABASE.getJdbcUrl().replace("/policy_test", "/" + database);
    var flywayV5 =
        org.flywaydb.core.Flyway.configure()
            .dataSource(url, DATABASE.getUsername(), DATABASE.getPassword())
            .target("5")
            .load();
    flywayV5.migrate();
    UUID legacyAudit = UUID.randomUUID();
    try (var connection =
            java.sql.DriverManager.getConnection(
                url, DATABASE.getUsername(), DATABASE.getPassword());
        var insert =
            connection.prepareStatement(
                "INSERT INTO iam_policy.policies(id,name,priority,enabled,version,document) VALUES (?,'legacy-policy',10,true,0,?::jsonb)")) {
      insert.setObject(1, UUID.randomUUID());
      insert.setString(
          2, json.writeValueAsString(document("invoices", "ALLOW", Set.of(), Set.of(), null)));
      insert.executeUpdate();
      try (var auditInsert =
          connection.prepareStatement(
              "INSERT INTO iam_audit.security_events(id,actor,event_type,resource,correlation_id,details) VALUES (?,'test','policy.created','legacy-policy','legacy-test','{}')")) {
        auditInsert.setObject(1, legacyAudit);
        auditInsert.executeUpdate();
      }
    }
    var upgraded =
        org.flywaydb.core.Flyway.configure()
            .dataSource(url, DATABASE.getUsername(), DATABASE.getPassword())
            .load();
    assertEquals(18, upgraded.migrate().migrationsExecuted);
    upgraded.validate();
    try (var connection =
            java.sql.DriverManager.getConnection(
                url, DATABASE.getUsername(), DATABASE.getPassword());
        var statement =
            connection.prepareStatement(
                "SELECT (SELECT count(*) FROM iam_audit.security_events WHERE id=?), (SELECT count(*) FROM iam_audit.outbox_events)")) {
      statement.setObject(1, legacyAudit);
      try (var row = statement.executeQuery()) {
        assertTrue(row.next());
        assertEquals(1, row.getInt(1));
        assertEquals(0, row.getInt(2));
      }
    }
    try (var connection =
            java.sql.DriverManager.getConnection(
                url, DATABASE.getUsername(), DATABASE.getPassword());
        var query = connection.createStatement();
        var row =
            query.executeQuery(
                "SELECT document->>'schemaVersion',version FROM iam_policy.policies WHERE name='legacy-policy'")) {
      assertTrue(row.next());
      assertEquals("1", row.getString(1));
      assertEquals(0, row.getLong(2));
    }
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

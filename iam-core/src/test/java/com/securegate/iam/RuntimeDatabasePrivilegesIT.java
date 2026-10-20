package com.securegate.iam;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securegate.events.SecurityEventType;
import com.securegate.iam.audit.application.port.AuditRecord;
import com.securegate.iam.audit.application.port.AuditTrail;
import com.securegate.iam.audit.application.port.EventTransport;
import com.securegate.iam.audit.infrastructure.JdbcOutboxDelivery;
import com.securegate.iam.audit.infrastructure.JdbcOutboxOperations;
import com.securegate.iam.shared.application.port.OperationContext;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RuntimeDatabasePrivilegesIT {
  private static final String LOGIN = "synthetic_runtime_login";
  private static final String PASSWORD = UUID.randomUUID().toString();
  private static final String TOKEN = UUID.randomUUID().toString();
  private static final Path SQL = Path.of("../infra/postgres/runtime-privileges.sql");

  @Container
  static final PostgreSQLContainer<?> DATABASE =
      new PostgreSQLContainer<>("postgres:16.15-alpine")
          .withDatabaseName("runtime_test")
          .withUsername("migration_owner")
          .withPassword(UUID.randomUUID().toString());

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) throws Exception {
    Flyway.configure()
        .dataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword())
        .load()
        .migrate();
    provision();
    try (var owner = owner();
        var statement = owner.createStatement()) {
      // Disposable test login only: production logins will be issued by Vault in 9A2b.
      statement.execute("CREATE ROLE " + LOGIN + " LOGIN INHERIT PASSWORD '" + PASSWORD + "'");
      statement.execute("GRANT securegate_iam_runtime TO " + LOGIN);
    }
    registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
    registry.add("spring.datasource.username", () -> LOGIN);
    registry.add("spring.datasource.password", () -> PASSWORD);
    registry.add("spring.flyway.enabled", () -> false);
    registry.add("iam.bootstrap.enabled", () -> true);
    registry.add("iam.bootstrap.token", () -> TOKEN);
  }

  private static Connection owner() throws SQLException {
    return DriverManager.getConnection(
        DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
  }

  private static void provision() throws Exception {
    try (var connection = owner();
        var statement = connection.createStatement()) {
      statement.execute(Files.readString(SQL));
    }
  }

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired AuditTrail audit;
  @Autowired PlatformTransactionManager manager;
  @Autowired ObjectMapper json;

  @Test
  void applicationProvisionsAndUpdatesUsersWithAuditAndOutboxAsRuntime() throws Exception {
    assertEquals(LOGIN, jdbc.queryForObject("SELECT current_user", String.class));
    String id = user().path("id").asText();
    assertEquals(200, call("GET", "/users/" + id, null).statusCode());
    assertEquals(
        200,
        call("PATCH", "/users/" + id + "/status", Map.of("status", "LOCKED", "version", 0))
            .statusCode());
    assertEquals(
        "LOCKED",
        jdbc.queryForObject(
            "SELECT status FROM iam_identity.users WHERE id=?::uuid", String.class, id));
    assertTrue(
        jdbc.queryForObject(
                "SELECT count(*) FROM iam_audit.security_events a JOIN iam_audit.outbox_events o ON o.event_id=a.id WHERE a.resource=?",
                Integer.class,
                id)
            >= 2);
    assertEquals(
        201,
        call("POST", "/roles", Map.of("name", "R_" + UUID.randomUUID().toString().replace("-", "")))
            .statusCode());
  }

  @Test
  void rollbackPreservesAtomicAuditAndOutboxWithRestrictedLogin() {
    String correlation = UUID.randomUUID().toString();
    new TransactionTemplate(manager)
        .executeWithoutResult(
            status -> {
              audit.record(
                  new AuditRecord(
                      new SecurityEventType("audit.changed"),
                      "synthetic",
                      new OperationContext("test", correlation),
                      Map.of()));
              status.setRollbackOnly();
            });
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM iam_audit.security_events WHERE correlation_id=?",
            Integer.class,
            correlation));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM iam_audit.outbox_events WHERE payload->>'correlationId'=?",
            Integer.class,
            correlation));
  }

  @Test
  void deliveryQuarantineHistoryAndRetentionWorkWithoutHistoryInsertPrivilege() throws Exception {
    user();
    var delivery = new JdbcOutboxDelivery(jdbc, manager);
    var claim = delivery.claim(30).orElseThrow();
    assertTrue(delivery.retry(claim, EventTransport.Outcome.INVALID_EVENT, 1));
    assertEquals(
        LOGIN,
        jdbc.queryForObject(
            "SELECT database_actor FROM iam_audit.outbox_operations WHERE event_id=? ORDER BY id DESC LIMIT 1",
            String.class,
            claim.eventId()));
    assertEquals(
        "QUARANTINE",
        jdbc.queryForObject(
            "SELECT action FROM iam_audit.outbox_operations WHERE event_id=? ORDER BY id DESC LIMIT 1",
            String.class,
            claim.eventId()));
    // The app needs metadata updates for delivery. Operator-only function remains denied.
    jdbc.update(
        "UPDATE iam_audit.outbox_events SET quarantined_at=NULL, published_at=clock_timestamp()-interval '8 days' WHERE event_id=?",
        claim.eventId());
    assertEquals(1, new JdbcOutboxOperations(jdbc, manager).purgePublished(7, 100));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM iam_audit.security_events WHERE id=?",
            Integer.class,
            claim.eventId()));
    assertEquals(
        2,
        jdbc.queryForObject(
            "SELECT count(*) FROM iam_audit.outbox_operations WHERE event_id=?",
            Integer.class,
            claim.eventId()));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "CREATE TABLE iam_identity.forbidden(id integer)",
        "ALTER TABLE iam_identity.users ADD COLUMN forbidden integer",
        "ALTER TABLE iam_audit.security_events DISABLE TRIGGER ALL",
        "DROP TABLE iam_identity.users CASCADE",
        "TRUNCATE iam_identity.users CASCADE",
        "CREATE SCHEMA forbidden",
        "CREATE ROLE forbidden",
        "SET ROLE migration_owner",
        "SELECT * FROM public.flyway_schema_history",
        "SELECT * FROM iam_audit.event_log",
        "SELECT * FROM iam_audit.event_receipts",
        "SELECT * FROM iam_audit.archived_months",
        "SELECT iam_audit.maintain_event_partitions(1)",
        "SELECT iam_audit.archive_event_month(date '2020-01-01',6)",
        "SELECT iam_audit.set_outbox_quarantine(NULL,true)",
        "UPDATE iam_audit.outbox_events SET payload=payload WHERE false",
        "UPDATE iam_audit.security_events SET actor=actor WHERE false",
        "DELETE FROM iam_audit.security_events WHERE false",
        "UPDATE iam_authorization.privileged_requests SET justification=justification WHERE false",
        "UPDATE iam_authorization.privileged_requests SET expires_at=expires_at WHERE false",
        "DELETE FROM iam_authorization.privileged_requests WHERE false",
        "DELETE FROM iam_authorization.privileged_issuances WHERE false",
        "UPDATE iam_authorization.privileged_issuances SET request_id=request_id WHERE false",
        "UPDATE iam_authorization.privileged_requests SET origin_correlation_id=origin_correlation_id WHERE false",
        "UPDATE iam_authorization.privileged_issuances SET origin_correlation_id=origin_correlation_id WHERE false",
        "INSERT INTO iam_audit.outbox_operations(event_id,action,reason) VALUES (gen_random_uuid(),'QUARANTINE','OPERATOR')",
        "TRUNCATE iam_audit.outbox_operations"
      })
  void runtimeCannotMigrateEscalateOrModifyAuditContent(String sql) throws Exception {
    try (var connection = DriverManager.getConnection(DATABASE.getJdbcUrl(), LOGIN, PASSWORD);
        var statement = connection.createStatement()) {
      SQLException denied = assertThrows(SQLException.class, () -> statement.execute(sql));
      assertEquals("42501", denied.getSQLState(), sql);
    }
  }

  @Test
  void runtimeCanPersistReadAndRevokePrivilegedRequestsWithoutChangingTheirContent()
      throws Exception {
    UUID userId = UUID.fromString(user().path("id").asText());
    UUID id = UUID.randomUUID();
    var now = java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    var repository =
        new com.securegate.iam.authorization.infrastructure.JdbcPrivilegedRequests(jdbc);
    repository.insert(
        new com.securegate.iam.authorization.application.port.PrivilegedRequests.Entry(
            id,
            userId,
            "database:diagnostics",
            "Investigar incidente de teste",
            now,
            now.plusSeconds(60),
            null));
    assertTrue(repository.find(id, userId).isPresent());
    assertTrue(repository.find(id, UUID.randomUUID()).isEmpty());
    assertTrue(repository.revoke(id, userId, now));
    assertFalse(repository.revoke(id, userId, now));
    assertEquals(now, repository.find(id, userId).orElseThrow().revokedAt());
    var issuances =
        new com.securegate.iam.authorization.infrastructure.JdbcPrivilegedIssuances(jdbc);
    assertTrue(issuances.start(id, now));
    assertFalse(issuances.start(id, now));
    assertTrue(issuances.finish(id, "synthetic-pam-lease", now.plusSeconds(10)));
    assertTrue(issuances.revoking(id));
    assertFalse(issuances.recordExpiration(id, now));
    assertTrue(issuances.recordExpiration(id, now.plusSeconds(10)));
    assertFalse(issuances.recordExpiration(id, now.plusSeconds(10)));
    assertEquals("REVOKING", issuances.find(id).orElseThrow().state());
    assertTrue(issuances.pending(now).stream().anyMatch(entry -> entry.requestId().equals(id)));
    assertTrue(issuances.defer(id, 0, now, now.plusSeconds(2)));
    assertFalse(issuances.defer(id, 0, now, now.plusSeconds(2)));
    assertEquals(1, issuances.find(id).orElseThrow().cleanupAttempts());
    assertTrue(issuances.pending(now).stream().noneMatch(entry -> entry.requestId().equals(id)));
    assertTrue(
        issuances.pending(now.plusSeconds(2)).stream()
            .anyMatch(entry -> entry.requestId().equals(id)));
    assertTrue(issuances.operations(now).deferred() >= 1);
    assertTrue(issuances.revoked(id));
    assertFalse(issuances.revoked(id));
    assertFalse(issuances.defer(id, 1, now, now.plusSeconds(2)));
    UUID expired = UUID.randomUUID();
    repository.insert(
        new com.securegate.iam.authorization.application.port.PrivilegedRequests.Entry(
            expired,
            userId,
            "database:diagnostics",
            "Investigar incidente de teste",
            now.minusSeconds(60),
            now.minusSeconds(1),
            null,
            "runtime-request-correlation"));
    assertTrue(repository.expired(now).stream().anyMatch(entry -> entry.id().equals(expired)));
    assertTrue(repository.deferObservation(expired, 0, now, now.plusSeconds(2)));
    assertFalse(repository.deferObservation(expired, 0, now, now.plusSeconds(2)));
    assertEquals(1, repository.find(expired, userId).orElseThrow().observationAttempts());
    assertTrue(repository.expired(now).stream().noneMatch(entry -> entry.id().equals(expired)));
    assertTrue(repository.operations(now).deferred() >= 1);
    assertTrue(repository.recordExpiration(expired, now));
    assertFalse(repository.recordExpiration(expired, now));
    assertFalse(repository.deferObservation(expired, 1, now, now.plusSeconds(4)));
    assertEquals(
        "runtime-request-correlation",
        repository.find(expired, userId).orElseThrow().originCorrelationId());
  }

  @Test
  void provisioningIsRepeatableAndRemovesUnexpectedColumnGrants() throws Exception {
    try (var connection = owner();
        var statement = connection.createStatement()) {
      statement.execute(
          "GRANT UPDATE(payload) ON iam_audit.outbox_events TO securegate_iam_runtime");
    }
    provision();
    provision();
    assertFalse(
        jdbc.queryForObject(
            "SELECT has_column_privilege(current_user,'iam_audit.outbox_events','payload','UPDATE')",
            Boolean.class));
    assertEquals(LOGIN, jdbc.queryForObject("SELECT current_user", String.class));
    user();
  }

  @Test
  void refusesElevatedGroupInsteadOfSilentlyAcceptingIt() throws Exception {
    try (var connection = owner();
        var statement = connection.createStatement()) {
      statement.execute("ALTER ROLE securegate_iam_runtime CREATEDB");
      try {
        assertThrows(SQLException.class, RuntimeDatabasePrivilegesIT::provision);
      } finally {
        statement.execute("ALTER ROLE securegate_iam_runtime NOCREATEDB");
      }
    }
  }

  @Test
  void futureTablesAreNotAutomaticallyGranted() throws Exception {
    try (var connection = owner();
        var statement = connection.createStatement()) {
      statement.execute("CREATE TABLE iam_identity.future_private(id integer)");
      try {
        assertFalse(
            jdbc.queryForObject(
                "SELECT has_table_privilege(current_user,'iam_identity.future_private','SELECT,INSERT,UPDATE,DELETE')",
                Boolean.class));
      } finally {
        statement.execute("DROP TABLE iam_identity.future_private");
      }
    }
  }

  private JsonNode user() throws Exception {
    var response =
        call(
            "POST",
            "/users",
            Map.of(
                "email",
                UUID.randomUUID() + "@example.test",
                "displayName",
                "Runtime test",
                "password",
                "Synthetic-user-password!"));
    assertEquals(201, response.statusCode(), response.body());
    return json.readTree(response.body());
  }

  private HttpResponse<String> call(String method, String path, Object body) throws Exception {
    try (HttpClient http = HttpClient.newHttpClient()) {
      var request =
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/admin" + path))
              .header("Authorization", "Bearer " + TOKEN)
              .header("Content-Type", "application/json");
      request.method(
          method,
          body == null
              ? HttpRequest.BodyPublishers.noBody()
              : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
      return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
  }
}

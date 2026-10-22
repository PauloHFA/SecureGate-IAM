package com.securegate.iam;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securegate.events.SecurityEvent;
import com.securegate.iam.audit.application.*;
import com.securegate.iam.audit.application.port.AuditEventStore;
import com.securegate.iam.audit.infrastructure.AuditDatabaseConnection;
import com.securegate.iam.audit.infrastructure.JdbcAuditEventQueries;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuditQueryIT {
  private static final String TOKEN = UUID.randomUUID().toString();
  private static final String PASSWORD = secret();
  private static Path credential;
  private static final Instant AT = Instant.now().truncatedTo(ChronoUnit.MICROS);
  private static final Instant FROM = AT.minusSeconds(3600);
  private static final Instant TO = AT.plusSeconds(3600);

  @Container
  static final PostgreSQLContainer<?> DATABASE =
      new PostgreSQLContainer<>("postgres:16.15-alpine")
          .withDatabaseName("audit_query")
          .withUsername("audit_owner")
          .withPassword(secret());

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) throws Exception {
    String url = DATABASE.getJdbcUrl().split("\\?")[0];
    Flyway.configure()
        .dataSource(url, DATABASE.getUsername(), DATABASE.getPassword())
        .load()
        .migrate();
    try (var connection =
        DriverManager.getConnection(url, DATABASE.getUsername(), DATABASE.getPassword())) {
      try (var setting = connection.prepareStatement("SELECT set_config(?, ?, false)")) {
        for (var entry :
            Map.of(
                    "securegate.securegate_audit_ingest_password",
                    PASSWORD,
                    "securegate.securegate_audit_maintenance_password",
                    secret())
                .entrySet()) {
          setting.setString(1, entry.getKey());
          setting.setString(2, entry.getValue());
          setting.execute();
        }
      }
      try (var statement = connection.createStatement()) {
        statement.execute(Files.readString(Path.of("../infra/postgres/audit-privileges.sql")));
      }
    }
    credential = Files.createTempFile("audit-query-", ".credential");
    Files.writeString(credential, PASSWORD);
    registry.add("spring.datasource.url", () -> url);
    registry.add("spring.datasource.username", DATABASE::getUsername);
    registry.add("spring.datasource.password", DATABASE::getPassword);
    registry.add("iam.audit.ingestion.enabled", () -> true);
    registry.add("iam.audit.ingestion.database-url", () -> url);
    registry.add("iam.audit.ingestion.username", () -> "securegate_audit_ingest_login");
    registry.add("iam.audit.ingestion.password-file", () -> credential.toString());
    registry.add("iam.audit.query.enabled", () -> true);
    registry.add("iam.bootstrap.enabled", () -> true);
    registry.add("iam.bootstrap.token", () -> TOKEN);
  }

  @AfterAll
  static void cleanup() throws Exception {
    if (credential != null) {
      Files.deleteIfExists(credential);
    }
  }

  @Autowired AuditQueryService service;
  @Autowired AuditEventStore store;
  @Autowired JdbcTemplate owner;
  @Autowired ObjectMapper json;

  @Autowired
  @Qualifier("auditIngestionConnection")
  AuditDatabaseConnection ingest;

  @LocalServerPort int port;
  private final HttpClient client = HttpClient.newHttpClient();

  @Test
  void stableTupleCursorTraversesEqualTimestampsWithoutGapsOrDuplicates() {
    String resource = resource();
    var ids = new ArrayList<UUID>();
    ids.add(UUID.fromString("00000000-0000-0000-0000-000000000001"));
    ids.add(UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"));
    for (int i = 0; i < 5; i++) {
      ids.add(UUID.randomUUID());
    }
    for (UUID id : ids) {
      store.append(event(id, AT, resource, "actor", "correlation", "test.query"));
    }
    var actual = new ArrayList<UUID>();
    String cursor = null;
    do {
      var page = service.list(FROM, TO, null, resource, null, null, 2, cursor);
      page.items().forEach(row -> actual.add(row.id()));
      cursor = page.nextCursor();
    } while (cursor != null);
    var expected =
        owner.queryForList(
            "SELECT id FROM iam_audit.event_log WHERE resource=? ORDER BY occurred_at DESC,id DESC",
            UUID.class,
            resource);
    assertEquals(expected, actual);
    assertEquals(7, new HashSet<>(actual).size());
  }

  @Test
  void cursorUsesStoredMicrosecondsRatherThanOriginalNanosecondEnvelope() {
    String resource = resource();
    Instant nano = AT.truncatedTo(ChronoUnit.SECONDS).plusNanos(123456789);
    for (int i = 0; i < 3; i++) {
      store.append(event(UUID.randomUUID(), nano, resource, "actor", "correlation", "test.query"));
    }
    var first = service.list(FROM, TO, null, resource, null, null, 1, null);
    assertNotEquals(
        first.items().getFirst().event().occurredAt(), first.items().getFirst().occurredAt());
    assertEquals(0, first.items().getFirst().occurredAt().getNano() % 1000);
    var second = service.list(FROM, TO, null, resource, null, null, 2, first.nextCursor());
    assertEquals(2, second.items().size());
    assertNull(second.nextCursor());
    assertFalse(
        second.items().stream().anyMatch(row -> row.id().equals(first.items().getFirst().id())));
  }

  @Test
  void windowIsInclusiveExclusiveAcrossUtcMonthlyPartitions() {
    Instant boundary =
        AT.atZone(ZoneOffset.UTC).withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS).toInstant();
    String resource = resource();
    Instant from = boundary.minusSeconds(1);
    Instant to = boundary.plusSeconds(1);
    for (Instant instant : List.of(from.minusNanos(1000), from, boundary, to)) {
      store.append(
          event(UUID.randomUUID(), instant, resource, "actor", "correlation", "test.query"));
    }
    var page = service.list(from, to, null, resource, null, null, 100, null);
    assertEquals(
        List.of(boundary, from), page.items().stream().map(AuditEventView::occurredAt).toList());
  }

  @Test
  void exactFiltersCombineSafelyAndSupportingIndexesAreValidOnParentAndChildren() {
    String resource = resource();
    var target = event(UUID.randomUUID(), AT, resource, "selected", "chosen", "test.query");
    store.append(target);
    store.append(event(UUID.randomUUID(), AT, resource, "other", "chosen", "test.query"));
    store.append(event(UUID.randomUUID(), AT, resource, "selected", "other", "test.query"));
    store.append(event(UUID.randomUUID(), AT, resource, "selected", "chosen", "test.other"));
    var page = service.list(FROM, TO, "test.query", resource, "selected", "chosen", 20, null);
    assertEquals(List.of(target.eventId()), page.items().stream().map(AuditEventView::id).toList());
    assertTrue(service.list(FROM, TO, null, "' OR 1=1 --", null, null, 20, null).items().isEmpty());
    assertEquals(
        5,
        owner.queryForObject(
            "SELECT count(*) FROM pg_index i JOIN pg_class c ON c.oid=i.indexrelid WHERE i.indrelid='iam_audit.event_log'::regclass AND i.indisvalid AND c.relname LIKE '%cursor_idx'",
            Integer.class));
    int before =
        owner.queryForObject(
            "SELECT count(*) FROM pg_inherits WHERE inhparent='iam_audit.event_log_actor_cursor_idx'::regclass",
            Integer.class);
    var futureMonth = AT.atZone(ZoneOffset.UTC).plusMonths(7).withDayOfMonth(1).toLocalDate();
    assertEquals(
        1,
        owner.queryForObject(
            "SELECT iam_audit.ensure_event_partitions(?,1)",
            Integer.class,
            java.sql.Date.valueOf(futureMonth)));
    assertEquals(
        before + 1,
        owner.queryForObject(
            "SELECT count(*) FROM pg_inherits WHERE inhparent='iam_audit.event_log_actor_cursor_idx'::regclass",
            Integer.class));
    for (String index : List.of("actor", "correlation")) {
      assertTrue(
          owner.queryForObject(
              "SELECT count(*)>=5 FROM pg_inherits WHERE inhparent=to_regclass(?)",
              Boolean.class,
              "iam_audit.event_log_" + index + "_cursor_idx"));
    }
  }

  @Test
  void insertAboveCursorDoesNotRepeatAndLateArrivalBelowCursorCanAppear() {
    String resource = resource();
    var originals = new ArrayList<SecurityEvent>();
    for (int i = 1; i <= 3; i++) {
      var event =
          event(
              UUID.randomUUID(), AT.plusSeconds(i), resource, "actor", "correlation", "test.query");
      originals.add(event);
      store.append(event);
    }
    var first = service.list(FROM, TO, null, resource, null, null, 1, null);
    var newer =
        event(UUID.randomUUID(), AT.plusSeconds(4), resource, "actor", "correlation", "test.query");
    var late =
        event(
            UUID.randomUUID(), AT.plusMillis(1500), resource, "actor", "correlation", "test.query");
    store.append(newer);
    store.append(late);
    var next = service.list(FROM, TO, null, resource, null, null, 100, first.nextCursor());
    assertEquals(
        List.of(originals.get(1).eventId(), late.eventId(), originals.getFirst().eventId()),
        next.items().stream().map(AuditEventView::id).toList());
  }

  @Test
  void httpRequiresBootstrapAndReturnsNoStoreEnvelopeOnlyToAuthorizedCaller() throws Exception {
    String resource = resource();
    store.append(
        event(UUID.randomUUID(), AT, resource, "private-actor", "correlation", "test.query"));
    var anonymous = get(Map.of("resource", resource), null, "");
    var incorrect = get(Map.of("resource", resource), "invalid", "");
    assertEquals(401, anonymous.statusCode());
    assertEquals(401, incorrect.statusCode());
    assertFalse(anonymous.body().contains("private-actor"));
    var valid = get(Map.of("resource", resource), TOKEN, "");
    assertEquals(200, valid.statusCode());
    assertEquals("no-store", valid.headers().firstValue("Cache-Control").orElseThrow());
    var page = json.readTree(valid.body());
    assertEquals(1, page.path("items").size());
    assertEquals("private-actor", page.path("items").get(0).path("event").path("actor").asText());
    assertTrue(page.path("nextCursor").isNull());
    assertEquals(
        "securegate_audit_ingest_login",
        ingest.jdbc().queryForObject("SELECT current_user", String.class));
  }

  @Test
  void httpRejectsMalformedUnknownRepeatedAndUnboundedParametersWithoutEchoingInput()
      throws Exception {
    for (var invalid :
        List.of(
            Map.of("limit", "0"),
            Map.of("limit", "101"),
            Map.of("limit", "no"),
            Map.of("from", "private-invalid-date"),
            Map.of("from", FROM.plusNanos(1).toString()),
            Map.of("to", FROM.plusSeconds(32 * 86400).toString()),
            Map.of("eventType", "invalid type"),
            Map.of("actor", ""),
            Map.of("cursor", "private-invalid-cursor"),
            Map.of("offset", "1"))) {
      var response = get(invalid, TOKEN, "");
      assertEquals(400, response.statusCode(), invalid.keySet().toString());
      assertFalse(response.body().contains("private-invalid"));
      assertFalse(response.body().contains("SELECT"));
    }
    assertEquals(400, get(Map.of(), TOKEN, "&limit=1&limit=2").statusCode());
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/admin/audit/events"))
            .header("Authorization", "Bearer " + TOKEN)
            .GET()
            .build();
    assertEquals(400, client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
  }

  @Test
  void cursorCannotAccidentallyContinueWithDifferentFiltersOrWindow() throws Exception {
    String resource = resource();
    for (int i = 0; i < 3; i++) {
      store.append(event(UUID.randomUUID(), AT, resource, "actor", "correlation", "test.query"));
    }
    var first = json.readTree(get(Map.of("resource", resource, "limit", "1"), TOKEN, "").body());
    String cursor = first.path("nextCursor").asText();
    assertEquals(400, get(Map.of("resource", "other", "cursor", cursor), TOKEN, "").statusCode());
    assertEquals(
        400,
        get(
                Map.of(
                    "resource",
                    resource,
                    "from",
                    FROM.minusSeconds(1).toString(),
                    "cursor",
                    cursor),
                TOKEN,
                "")
            .statusCode());
    var next = get(Map.of("resource", resource, "cursor", cursor), TOKEN, "");
    assertEquals(200, next.statusCode());
    assertEquals(2, json.readTree(next.body()).path("items").size());
  }

  @Test
  void lockTimeoutBecomesSanitized503AndReadOnlyStateDoesNotLeakToIngestion() throws Exception {
    try (var blocker = DATABASE.createConnection("")) {
      blocker.setAutoCommit(false);
      try (var statement = blocker.createStatement()) {
        statement.execute("LOCK TABLE iam_audit.event_log IN ACCESS EXCLUSIVE MODE");
      }
      var response = get(Map.of(), TOKEN, "");
      assertEquals(503, response.statusCode());
      assertFalse(response.body().contains("SELECT"));
      assertFalse(response.body().contains("audit_owner"));
      blocker.rollback();
    }
    var manager = ingest.transactions();
    new JdbcAuditEventQueries(ingest.jdbc(), json, manager);
    var read = new TransactionTemplate(manager);
    read.setReadOnly(true);
    var denied =
        assertThrows(
            DataAccessException.class,
            () ->
                read.execute(
                    status ->
                        ingest
                            .jdbc()
                            .update(
                                "INSERT INTO iam_audit.event_log(id) VALUES (?)",
                                UUID.randomUUID())));
    assertEquals("25006", ((java.sql.SQLException) denied.getMostSpecificCause()).getSQLState());
    assertEquals(
        AuditEventStore.Result.APPENDED,
        store.append(
            event(UUID.randomUUID(), AT, resource(), "actor", "correlation", "test.query")));
    assertEquals(200, get(Map.of(), TOKEN, "").statusCode());
  }

  private HttpResponse<String> get(Map<String, String> extra, String token, String suffix)
      throws Exception {
    var parameters = new LinkedHashMap<String, String>();
    parameters.put("from", FROM.toString());
    parameters.put("to", TO.toString());
    parameters.putAll(extra);
    var pairs = new ArrayList<String>();
    parameters.forEach(
        (key, value) ->
            pairs.add(
                URLEncoder.encode(key, StandardCharsets.UTF_8)
                    + "="
                    + URLEncoder.encode(value, StandardCharsets.UTF_8)));
    var request =
        HttpRequest.newBuilder(
                URI.create(
                    "http://127.0.0.1:"
                        + port
                        + "/api/admin/audit/events?"
                        + String.join("&", pairs)
                        + suffix))
            .timeout(Duration.ofSeconds(10))
            .GET();
    if (token != null) {
      request.header("Authorization", "Bearer " + token);
    }
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static String resource() {
    return "query:" + UUID.randomUUID();
  }

  private static String secret() {
    return UUID.randomUUID().toString().replace("-", "")
        + UUID.randomUUID().toString().replace("-", "");
  }

  private static SecurityEvent event(
      UUID id, Instant at, String resource, String actor, String correlation, String type) {
    return new SecurityEvent(
        id, type, 1, at, "iam-core", correlation, actor, Map.of("resource", resource), Map.of());
  }
}

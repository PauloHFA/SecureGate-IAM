package com.securegate.iam;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.securegate.iam.audit.application.port.AuditTrail;
import com.securegate.iam.authorization.application.*;
import com.securegate.iam.authorization.application.port.*;
import com.securegate.iam.authorization.infrastructure.*;
import com.securegate.iam.shared.infrastructure.JdbcUnitOfWork;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
class PamCleanupRecoveryIT {
  @Container
  static final PostgreSQLContainer<?> DATABASE =
      new PostgreSQLContainer<>("postgres:16.15-alpine").withPassword(UUID.randomUUID().toString());

  static JdbcTemplate jdbc;
  static DriverManagerDataSource dataSource;
  final Instant now = Instant.parse("2026-10-20T19:00:00Z");
  final UUID user = UUID.randomUUID();
  final PamCredentials vault = mock(PamCredentials.class);
  final AuditTrail audit = mock(AuditTrail.class);
  JdbcPrivilegedIssuances issuances;

  @BeforeAll
  static void migrate() {
    dataSource =
        new DriverManagerDataSource(
            DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
    Flyway.configure().dataSource(dataSource).load().migrate();
    jdbc = new JdbcTemplate(dataSource);
  }

  @BeforeEach
  void prepare() {
    jdbc.execute(
        "TRUNCATE iam_authorization.privileged_issuances,iam_authorization.privileged_requests");
    jdbc.update(
        "INSERT INTO iam_identity.users(id,email,display_name,status) VALUES (?,?,?,'ACTIVE')",
        user,
        user + "@example.test",
        "Synthetic operator");
    issuances = new JdbcPrivilegedIssuances(jdbc);
  }

  UUID entry(String state, Instant created) {
    UUID id = UUID.randomUUID();
    new JdbcPrivilegedRequests(jdbc)
        .insert(
            new PrivilegedRequests.Entry(
                id,
                user,
                "database:diagnostics",
                "Diagnostico sintetico",
                created,
                created.plusSeconds(120),
                null));
    assertTrue(issuances.start(id, created, "synthetic-origin"));
    if (state.equals("FAILED")) {
      assertTrue(issuances.abort(id, null, null));
    } else if (!state.equals("ISSUING")) {
      assertTrue(issuances.finish(id, "synthetic-" + id, created.plusSeconds(60)));
      if (state.equals("REVOKING")) {
        assertTrue(issuances.revoking(id));
      }
    }
    return id;
  }

  PamCredentialService worker(Instant at) {
    // A new repository/service reproduces process recreation; scheduling lives in PostgreSQL.
    return new PamCredentialService(
        new JdbcPrivilegedRequests(jdbc),
        new JdbcPrivilegedIssuances(jdbc),
        vault,
        mock(ResourceAccessService.class),
        new JdbcUnitOfWork(new TransactionTemplate(new DataSourceTransactionManager(dataSource))),
        audit,
        Clock.fixed(at, ZoneOffset.UTC));
  }

  @Test
  void tenFailingItemsYieldToLaterItemsAndRecoverAfterProcessRecreation() {
    var failing = new ArrayList<UUID>();
    for (int index = 0; index < 10; index++) {
      UUID id = entry("REVOKING", now.minusSeconds(120 + index));
      failing.add(id);
      doThrow(new IllegalStateException("private backend detail"))
          .when(vault)
          .revoke("synthetic-" + id);
    }
    UUID healthy = entry("REVOKING", now.minusSeconds(61));
    UUID healthySecond = entry("REVOKING", now.minusSeconds(62));
    assertThrows(IllegalStateException.class, worker(now)::maintain);
    var before = issuances.operations(now);
    assertEquals(12, before.pending());
    assertEquals(10, before.deferred());
    assertEquals(2, before.ready());
    assertEquals(10, before.retrying());
    worker(now).maintain();
    assertEquals("REVOKED", issuances.find(healthy).orElseThrow().state());
    assertEquals("REVOKED", issuances.find(healthySecond).orElseThrow().state());
    assertTrue(issuances.pending(now.plusMillis(1999)).isEmpty());
    reset(vault);
    worker(now.plusSeconds(2)).maintain();
    assertEquals(0, issuances.operations(now.plusSeconds(2)).pending());
    for (UUID id : failing) {
      assertEquals("REVOKED", issuances.find(id).orElseThrow().state());
      assertEquals(1, issuances.find(id).orElseThrow().cleanupAttempts());
      assertFalse(issuances.defer(id, 1, now, now.plusSeconds(4)));
    }
    verify(vault, never()).issue(anyInt());
  }

  @Test
  void concurrentRetryUpdatesHaveOneWinnerAndCannotReopenTerminalState() throws Exception {
    UUID id = entry("REVOKING", now.minusSeconds(60));
    try (var workers = Executors.newFixedThreadPool(2)) {
      var gate = new CountDownLatch(1);
      Callable<Boolean> schedule =
          () -> {
            gate.await();
            return new JdbcPrivilegedIssuances(jdbc).defer(id, 0, now, now.plusSeconds(2));
          };
      var first = workers.submit(schedule);
      var second = workers.submit(schedule);
      gate.countDown();
      assertNotEquals(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
    }
    assertTrue(issuances.revoked(id));
    assertFalse(issuances.defer(id, 1, now, now.plusSeconds(4)));
    assertEquals(0, issuances.operations(now).pending());
  }

  @Test
  void auditFailureRollsBackRevocationAndRetryDoesNotFabricateAcknowledgement() {
    UUID id = entry("REVOKING", now.minusSeconds(120));
    doThrow(new IllegalStateException("synthetic audit failure"))
        .when(audit)
        .record(argThat(record -> record.type().value().equals("pam.credential.revoked")));
    assertThrows(IllegalStateException.class, worker(now)::maintain);
    assertEquals("REVOKING", issuances.find(id).orElseThrow().state());
    assertEquals(1, issuances.operations(now).deferred());
    reset(audit);
    worker(now.plusSeconds(2)).maintain();
    assertEquals("REVOKED", issuances.find(id).orElseThrow().state());
    verify(vault, times(2)).revoke("synthetic-" + id);
    verify(audit)
        .record(
            argThat(
                record ->
                    record.type().value().equals("pam.credential.revoked")
                        && record.context().correlationId().equals("synthetic-origin")));
  }

  @Test
  void twentyFailedRequestObservationsYieldToOtherRequestsAndResumeAtStoredDeadline() {
    var requests = new JdbcPrivilegedRequests(jdbc);
    var failing = new HashSet<UUID>();
    for (int index = 0; index < 20; index++) {
      failing.add(entry("ISSUING", now.minusSeconds(200 + index)));
    }
    UUID healthy = entry("ISSUING", now.minusSeconds(180));
    doThrow(new IllegalStateException("synthetic audit unavailable"))
        .when(audit)
        .record(
            argThat(
                record -> failing.contains(UUID.fromString(record.details().get("requestId")))));
    assertThrows(IllegalStateException.class, requestWorker(now)::observeExpirations);
    assertEquals(21, requests.operations(now).pending());
    assertEquals(20, requests.operations(now).deferred());
    assertEquals(1, requests.operations(now).ready());
    requestWorker(now).observeExpirations();
    assertEquals(20, requests.operations(now).pending());
    assertFalse(requests.recordExpiration(healthy, now));
    assertTrue(requests.expired(now.plusMillis(1999)).isEmpty());
    reset(audit);
    requestWorker(now.plusSeconds(2)).observeExpirations();
    assertEquals(0, requests.operations(now.plusSeconds(2)).pending());
    for (UUID id : failing) {
      assertEquals(1, requests.find(id, user).orElseThrow().observationAttempts());
      assertFalse(requests.deferObservation(id, 1, now, now.plusSeconds(4)));
    }
  }

  PrivilegedRequestService requestWorker(Instant at) {
    return new PrivilegedRequestService(
        new JdbcPrivilegedRequests(jdbc),
        new JdbcUnitOfWork(new TransactionTemplate(new DataSourceTransactionManager(dataSource))),
        audit,
        Clock.fixed(at, ZoneOffset.UTC));
  }

  @Test
  void observationRetriesCannotReopenRevokedOrObservedRequestsAndCountersSaturate()
      throws Exception {
    var requests = new JdbcPrivilegedRequests(jdbc);
    UUID id = entry("ISSUING", now.minusSeconds(180));
    try (var workers = Executors.newFixedThreadPool(2)) {
      var gate = new CountDownLatch(1);
      Callable<Boolean> schedule =
          () -> {
            gate.await();
            return requests.deferObservation(id, 0, now, now.plusSeconds(2));
          };
      var first = workers.submit(schedule);
      var second = workers.submit(schedule);
      gate.countDown();
      assertNotEquals(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
    }
    for (int index = 1; index < 35; index++) {
      assertTrue(requests.deferObservation(id, Math.min(30, index), now, now.plusSeconds(60)));
    }
    assertEquals(30, requests.find(id, user).orElseThrow().observationAttempts());
    assertTrue(requests.recordExpiration(id, now));
    assertFalse(requests.deferObservation(id, 30, now, now.plusSeconds(120)));
    UUID revoked = entry("ISSUING", now.minusSeconds(30));
    assertTrue(requests.revoke(revoked, user, now));
    assertFalse(requests.deferObservation(revoked, 0, now.plusSeconds(120), now.plusSeconds(122)));
    assertEquals(0, requests.operations(now.plusSeconds(120)).pending());
  }

  @Test
  void ambiguousFailuresRemainVisibleAndAreNeverReissued() {
    UUID id = entry("FAILED", now.minusSeconds(120));
    UUID stale = entry("ISSUING", now.minusSeconds(31));
    entry("ISSUING", now.minusSeconds(29));
    entry("ISSUED", now);
    assertEquals(1, issuances.operations(now).pending());
    assertEquals(1, issuances.operations(now).ambiguous());
    worker(now).maintain();
    assertEquals("FAILED", issuances.find(stale).orElseThrow().state());
    assertEquals("FAILED", issuances.find(id).orElseThrow().state());
    assertEquals(2, issuances.operations(now).ambiguous());
    assertEquals(0, issuances.operations(now).pending());
    verifyNoInteractions(vault);
  }
}

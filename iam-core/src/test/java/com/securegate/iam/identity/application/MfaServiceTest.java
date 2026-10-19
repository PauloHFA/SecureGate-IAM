package com.securegate.iam.identity.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.securegate.iam.audit.application.port.AuditTrail;
import com.securegate.iam.identity.application.MfaService.Session;
import com.securegate.iam.identity.application.port.*;
import com.securegate.iam.identity.application.port.MfaStore.Factor;
import com.securegate.iam.identity.domain.TotpSecret;
import com.securegate.iam.shared.application.port.*;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MfaServiceTest {
  private final MfaStore store = mock(MfaStore.class);
  private final MfaProofs proofs = mock(MfaProofs.class);
  private final MfaSecretProtector secrets = mock(MfaSecretProtector.class);
  private final AuditTrail audit = mock(AuditTrail.class);
  private final Clock clock = mock(Clock.class);
  private final AtomicReference<Factor> factor =
      new AtomicReference<>(
          new Factor(UUID.randomUUID(), "NONE", null, null, null, null, -1, 0, null));
  private final UnitOfWork transactions =
      new UnitOfWork() {
        public <T> T execute(Supplier<T> action) {
          return action.get();
        }
      };
  private final Session session =
      new Session(UUID.randomUUID(), 0, UUID.randomUUID(), Instant.ofEpochSecond(50));
  private final OperationContext context = new OperationContext("user", "mfa-test");
  private final MfaService service =
      new MfaService(store, proofs, secrets, audit, transactions, clock);

  @BeforeEach
  void setup() {
    when(clock.instant()).thenReturn(Instant.ofEpochSecond(59));
    when(store.lockCurrentUser(session.userId(), 0)).thenReturn(true);
    when(store.lockOpenSession(any())).thenReturn(true);
    when(store.load(session.userId())).thenAnswer(call -> factor.get());
    doAnswer(
            call -> {
              factor.set(call.getArgument(1));
              return null;
            })
        .when(store)
        .save(eq(session.userId()), any());
    when(store.passwordMatches(session.userId(), "correct-password")).thenReturn(true);
    when(secrets.protect(any(), any(), any())).thenReturn("vault:v1:AAAA");
    when(secrets.unprotect(any(), any(), any()))
        .thenReturn(TotpSecret.parse("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"));
    when(proofs.current(any(), anyLong(), any())).thenReturn(Optional.empty());
  }

  @Test
  void enrollsConfirmsAndVerifiesOnlyNewCounters() {
    var enrollment = service.enroll(session, "correct-password", context);
    assertEquals("SUCCESS", enrollment.outcome());
    assertTrue(enrollment.secret().matches("[A-Z2-7]{32}"));
    assertFalse(enrollment.toString().contains(enrollment.secret()));
    assertEquals("PENDING", service.status(session).state());
    assertEquals("Factor[redacted]", factor.get().toString());
    var confirmed = service.confirm(session, "287082", context);
    assertEquals("SUCCESS", confirmed.outcome());
    assertEquals(10, confirmed.recoveryCodes().stream().distinct().count());
    assertTrue(
        confirmed.recoveryCodes().stream().allMatch(code -> code.matches("[A-Za-z0-9_-]{22}")));
    verify(store)
        .recovery(
            eq(factor.get().id()),
            argThat(
                hashes ->
                    hashes.size() == 10
                        && hashes.stream().allMatch(hash -> hash.matches("[a-f0-9]{64}"))));
    assertEquals("DENIED", service.verify(session, "TOTP", "287082", context).outcome());
    when(clock.instant()).thenReturn(Instant.ofEpochSecond(89));
    assertEquals("SUCCESS", service.verify(session, "TOTP", "359152", context).outcome());
    assertEquals(2, factor.get().lastCounter());
    assertEquals(0, factor.get().failures());
    verify(audit, atLeastOnce())
        .record(argThat(event -> event.details().keySet().equals(java.util.Set.of("method"))));
    assertEquals("DENIED", service.enroll(session, "correct-password", context).outcome());
  }

  @Test
  void locksAcrossOperationsAndResetsFailureWindowAfterExpiry() {
    for (int i = 0; i < 4; i++) {
      assertEquals("DENIED", service.enroll(session, "wrong", context).outcome());
    }
    assertEquals("LOCKED", service.confirm(session, "000000", context).outcome());
    assertNotNull(service.status(session).blockedUntil());
    assertEquals("LOCKED", service.enroll(session, "correct-password", context).outcome());
    assertEquals("LOCKED", service.confirm(session, "287082", context).outcome());
    assertEquals("LOCKED", service.verify(session, "RECOVERY", "A".repeat(22), context).outcome());
    when(clock.instant()).thenReturn(Instant.ofEpochSecond(360));
    assertEquals("DENIED", service.enroll(session, "wrong", context).outcome());
    assertEquals(1, factor.get().failures());
    assertNull(service.status(session).blockedUntil());
  }

  @Test
  void requiresRecentLoginAndCurrentIdentity() {
    when(clock.instant()).thenReturn(Instant.ofEpochSecond(350));
    assertEquals("DENIED", service.enroll(session, "correct-password", context).outcome());
    when(clock.instant()).thenReturn(Instant.ofEpochSecond(40));
    assertEquals("DENIED", service.enroll(session, "correct-password", context).outcome());
    when(store.lockCurrentUser(session.userId(), 0)).thenReturn(false);
    assertThrows(MfaService.StaleSession.class, () -> service.status(session));
    assertThrows(MfaService.StaleSession.class, () -> service.status(null));
    assertThrows(
        MfaService.StaleSession.class,
        () -> service.status(new Session(null, 0, session.sid(), null)));
    assertThrows(
        MfaService.StaleSession.class,
        () -> service.status(new Session(session.userId(), 0, null, null)));
    assertThrows(
        MfaService.StaleSession.class,
        () -> service.status(new Session(session.userId(), -1, session.sid(), null)));
  }

  @Test
  void pendingEnrollmentExpiresAndCannotMoveBetweenSessionsOrIdentityVersions() {
    service.enroll(session, "correct-password", context);
    Session other = new Session(session.userId(), 0, UUID.randomUUID(), session.authenticatedAt());
    assertEquals("DENIED", service.confirm(other, "287082", context).outcome());
    Factor original = factor.get();
    factor.set(
        new Factor(
            original.id(),
            "PENDING",
            original.ciphertext(),
            session.sid(),
            1L,
            original.pendingExpiresAt(),
            -1,
            0,
            null));
    assertEquals("DENIED", service.confirm(session, "287082", context).outcome());
    factor.set(original);
    when(clock.instant()).thenReturn(original.pendingExpiresAt());
    assertEquals("DENIED", service.confirm(session, "287082", context).outcome());
    verify(store, never()).recovery(any(), any());
  }

  @Test
  void recoveryUsesOnlyHashesAndUnsupportedOrInvalidMethodsFail() {
    service.enroll(session, "correct-password", context);
    List<String> codes = service.confirm(session, "287082", context).recoveryCodes();
    when(store.consumeRecovery(eq(factor.get().id()), any())).thenReturn(true, false);
    assertEquals(
        "SUCCESS", service.verify(session, "RECOVERY", codes.getFirst(), context).outcome());
    assertEquals(
        "DENIED", service.verify(session, "RECOVERY", codes.getFirst(), context).outcome());
    assertEquals("DENIED", service.verify(session, "FAKE", "287082", context).outcome());
    assertEquals("DENIED", service.verify(session, "RECOVERY", null, context).outcome());
    assertEquals("DENIED", service.verify(session, "RECOVERY", "short", context).outcome());
    verify(audit)
        .record(argThat(event -> event.type().value().equals("identity.mfa.recoveryused")));
  }

  @Test
  void unavailableSecretProtectionDoesNotBecomeAnInvalidCodeAttempt() {
    service.enroll(session, "correct-password", context);
    doThrow(new MfaSecretProtector.Unavailable()).when(secrets).unprotect(any(), any(), any());
    assertThrows(
        MfaSecretProtector.Unavailable.class, () -> service.confirm(session, "287082", context));
    assertEquals(0, factor.get().failures());
    verify(store, never()).proof(any(), anyLong(), any(), any(), any(), any());
  }
}

package com.securegate.iam.authorization.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.securegate.iam.audit.application.port.AuditRecord;
import com.securegate.iam.audit.application.port.AuditTrail;
import com.securegate.iam.authorization.application.port.PrivilegedRequests;
import com.securegate.iam.policy.application.port.PolicyDecisions;
import com.securegate.iam.shared.application.ResourceNotFoundException;
import com.securegate.iam.shared.application.port.UnitOfWork;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class PrivilegedRequestServiceTest {
  final Instant now = Instant.parse("2026-10-20T12:00:00Z");
  final UUID user = UUID.randomUUID();
  final PrivilegedRequests requests = mock(PrivilegedRequests.class);
  final AuditTrail audit = mock(AuditTrail.class);
  final PrivilegedRequestService service =
      new PrivilegedRequestService(
          requests,
          new UnitOfWork() {
            public <T> T execute(Supplier<T> operation) {
              return operation.get();
            }
          },
          audit,
          Clock.fixed(now, ZoneOffset.UTC));

  ResourceAccessService.Permit permit(Instant deadline) {
    return new ResourceAccessService.Permit(
        user,
        Set.of("pam:request"),
        deadline,
        new PolicyDecisions.Decision(false, Set.of("pam:request"), 300, false, List.of(), 20));
  }

  @Test
  void capsDeadlineAndKeepsJustificationOutOfResponseAndAudit() {
    var result =
        service.create(permit(now.plusSeconds(8)), "Investigar incidente privado", 300, "pam-test");
    assertEquals(now.plusSeconds(8), result.expiresAt());
    var entry = ArgumentCaptor.forClass(PrivilegedRequests.Entry.class);
    verify(requests).insert(entry.capture());
    assertEquals(user, entry.getValue().userId());
    assertEquals("Investigar incidente privado", entry.getValue().justification());
    assertFalse(entry.getValue().toString().contains("privado"));
    var event = ArgumentCaptor.forClass(AuditRecord.class);
    verify(audit).record(event.capture());
    assertEquals("pam.request.authorized", event.getValue().type().value());
    assertEquals(Set.of("requestId", "expiresAt"), event.getValue().details().keySet());
  }

  @ParameterizedTest
  @ValueSource(ints = {-1, 0, 29, 301, Integer.MAX_VALUE})
  void rejectsInvalidTtl(int ttl) {
    assertThrows(
        IllegalArgumentException.class,
        () -> service.create(permit(now.plusSeconds(300)), "Investigar incidente", ttl, "test"));
    verifyNoInteractions(requests, audit);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "curta", "          ", "incidente\nprivado"})
  void rejectsInvalidJustification(String reason) {
    assertThrows(
        IllegalArgumentException.class,
        () -> service.create(permit(now.plusSeconds(300)), reason, 30, "test"));
    verifyNoInteractions(requests, audit);
  }

  @Test
  void expiredPermitNeverCreatesRequest() {
    assertThrows(
        ResourceAccessService.Rejected.class,
        () -> service.create(permit(now), "Investigar incidente", 30, "test"));
    verifyNoInteractions(requests, audit);
  }

  @Test
  void missingOrForeignRequestIsInvisible() {
    UUID id = UUID.randomUUID();
    when(requests.find(id, user)).thenReturn(Optional.empty());
    assertThrows(ResourceNotFoundException.class, () -> service.get(id, user));
    assertThrows(ResourceNotFoundException.class, () -> service.revoke(id, user, "test"));
    verifyNoInteractions(audit);
    verify(requests, never()).revoke(any(), any(), any());
  }

  @Test
  void expirationAndRevocationAreTerminalAndDoNotMoveDeadline() {
    UUID id = UUID.randomUUID();
    var expired =
        new PrivilegedRequests.Entry(
            id,
            user,
            "database:diagnostics",
            "Investigar incidente",
            now.minusSeconds(60),
            now,
            null);
    when(requests.find(id, user)).thenReturn(Optional.of(expired));
    assertEquals("EXPIRED", service.get(id, user).status());
    assertEquals("EXPIRED", service.revoke(id, user, "test").status());
    verifyNoInteractions(audit);
    var revoked =
        new PrivilegedRequests.Entry(
            id,
            user,
            expired.target(),
            expired.justification(),
            expired.createdAt(),
            expired.expiresAt(),
            now.minusSeconds(1));
    when(requests.find(id, user)).thenReturn(Optional.of(revoked));
    assertEquals("REVOKED", service.get(id, user).status());
    assertEquals(now, service.get(id, user).expiresAt());
  }
}

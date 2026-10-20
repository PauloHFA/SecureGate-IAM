package com.securegate.iam.authorization.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.securegate.iam.audit.application.port.*;
import com.securegate.iam.authorization.application.port.*;
import com.securegate.iam.shared.application.*;
import com.securegate.iam.shared.application.port.UnitOfWork;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PamCredentialServiceTest {
  final UUID id = UUID.randomUUID(), user = UUID.randomUUID();
  final Instant now = Instant.parse("2026-10-20T15:00:00Z");
  final PrivilegedRequests requests = mock(PrivilegedRequests.class);
  final PrivilegedIssuances issuances = mock(PrivilegedIssuances.class);
  final PamCredentials vault = mock(PamCredentials.class);
  final PamCredentials.Issued credential = mock(PamCredentials.Issued.class);
  final ResourceAccessService access = mock(ResourceAccessService.class);
  final AuditTrail audit = mock(AuditTrail.class);
  final ResourceAccessService.Permit permit =
      new ResourceAccessService.Permit(user, Set.of("pam:issue"), now.plusSeconds(300), null);
  final PamCredentialService service =
      new PamCredentialService(
          requests,
          issuances,
          vault,
          access,
          new UnitOfWork() {
            public <T> T execute(Supplier<T> task) {
              return task.get();
            }
          },
          audit,
          Clock.fixed(now, ZoneOffset.UTC));

  @BeforeEach
  void setup() {
    when(access.authorize("token", ResourceAccessService.Operation.PRIVILEGED_ISSUE, "127.0.0.1"))
        .thenReturn(permit);
    when(requests.lock(id, user))
        .thenReturn(
            Optional.of(
                new PrivilegedRequests.Entry(
                    id,
                    user,
                    "database:diagnostics",
                    "Investigar incidente",
                    now,
                    now.plusSeconds(120),
                    null)));
    when(issuances.start(eq(id), any(), eq("pam-unit"))).thenReturn(true);
    when(issuances.finish(eq(id), anyString(), any())).thenReturn(true);
    when(issuances.abort(eq(id), any(), any())).thenReturn(true);
    when(vault.issue(60)).thenReturn(credential);
    when(credential.leaseId()).thenReturn("synthetic-lease");
    when(credential.username()).thenReturn("synthetic-user");
    when(credential.password()).thenReturn("synthetic-password");
    when(credential.ttlSeconds()).thenReturn(60);
  }

  PamCredentialService.Secret issue() {
    return service.issue(id, "token", "127.0.0.1", "pam-unit");
  }

  @Test
  void reauthorizesBeforeDeliveryAndPersistsOnlyLeaseMetadata() {
    var result = issue();
    assertEquals(now.plusSeconds(60), result.expiresAt());
    assertFalse(result.toString().contains("synthetic-password"));
    verify(access, times(2))
        .authorize("token", ResourceAccessService.Operation.PRIVILEGED_ISSUE, "127.0.0.1");
    verify(credential).delivered();
    verify(credential).close();
    verify(issuances).finish(id, "synthetic-lease", now.plusSeconds(60));
    var event = ArgumentCaptor.forClass(AuditRecord.class);
    verify(audit).record(event.capture());
    assertEquals(
        Map.of(
            "requestId",
            id.toString(),
            "expiresAt",
            now.plusSeconds(60).toString(),
            "meaning",
            "ISSUANCE_COMMITTED"),
        event.getValue().details());
  }

  @Test
  void consumedRequestNeverCallsVaultAgain() {
    when(issuances.start(eq(id), any(), eq("pam-unit"))).thenReturn(false);
    assertThrows(ConcurrentUpdateException.class, this::issue);
    verifyNoInteractions(vault);
    verify(issuances, never()).abort(any(), any(), any());
  }

  @Test
  void foreignRequestDoesNotConsumeOrIssue() {
    when(requests.lock(id, user)).thenReturn(Optional.empty());
    assertThrows(ResourceNotFoundException.class, this::issue);
    verifyNoInteractions(vault, issuances);
  }

  @Test
  void revokedRequestCannotIssue() {
    when(requests.lock(id, user))
        .thenReturn(
            Optional.of(
                new PrivilegedRequests.Entry(
                    id,
                    user,
                    "database:diagnostics",
                    "Investigar incidente",
                    now,
                    now.plusSeconds(120),
                    now)));
    assertThrows(ConcurrentUpdateException.class, this::issue);
    verifyNoInteractions(vault, issuances);
  }

  @Test
  void insufficientRemainingLifetimeNeverConsumes() {
    when(requests.lock(id, user))
        .thenReturn(
            Optional.of(
                new PrivilegedRequests.Entry(
                    id,
                    user,
                    "database:diagnostics",
                    "Investigar incidente",
                    now,
                    now.plusSeconds(14),
                    null)));
    assertThrows(ResourceAccessService.Rejected.class, this::issue);
    verifyNoInteractions(vault, issuances);
  }

  @Test
  void ambiguousBackendFailureIsDurablyConsumed() {
    when(vault.issue(60)).thenThrow(new IllegalStateException("private backend response"));
    var failure = assertThrows(PamCredentialService.Unavailable.class, this::issue);
    assertNull(failure.getCause());
    verify(issuances).abort(id, null, null);
    verify(issuances, never()).finish(any(), any(), any());
  }

  @Test
  void policyChangeAfterVaultEmissionPreventsDeliveryAndQueuesKnownLease() {
    when(access.authorize("token", ResourceAccessService.Operation.PRIVILEGED_ISSUE, "127.0.0.1"))
        .thenReturn(permit)
        .thenThrow(new ResourceAccessService.Rejected("insufficient_scope"));
    assertThrows(ResourceAccessService.Rejected.class, this::issue);
    verify(credential, never()).delivered();
    verify(credential).close();
    verify(issuances).abort(id, "synthetic-lease", now.plusSeconds(60));
  }

  @Test
  void reducedPolicyBudgetAfterEmissionDoesNotReturnAnOverlongCredential() {
    when(access.authorize("token", ResourceAccessService.Operation.PRIVILEGED_ISSUE, "127.0.0.1"))
        .thenReturn(
            permit,
            new ResourceAccessService.Permit(user, Set.of("pam:issue"), now.plusSeconds(30), null));
    assertThrows(ResourceAccessService.Rejected.class, this::issue);
    verify(credential, never()).delivered();
  }

  @Test
  void auditFailureNeverDeliversTheSecret() {
    doThrow(new IllegalStateException("private audit failure"))
        .when(audit)
        .record(argThat(event -> event.type().value().equals("pam.credential.issued")));
    assertThrows(PamCredentialService.Unavailable.class, this::issue);
    verify(credential, never()).delivered();
    verify(credential).close();
    verify(issuances).abort(id, "synthetic-lease", now.plusSeconds(60));
  }

  @Test
  void staleClaimIsFailedWithoutReissuing() {
    when(issuances.pending(now))
        .thenReturn(List.of(new PrivilegedIssuances.Entry(id, "ISSUING", null, null)));
    when(issuances.abandoned(id, now.minusSeconds(30))).thenReturn(true);
    service.maintain();
    verifyNoInteractions(vault);
    verify(audit).record(argThat(event -> event.type().value().equals("pam.credential.failed")));
  }

  @Test
  void revocationFailureKeepsLeaseForRetry() {
    when(issuances.pending(now))
        .thenReturn(List.of(new PrivilegedIssuances.Entry(id, "REVOKING", "synthetic-lease", now)));
    doThrow(new IllegalStateException("unavailable"))
        .doNothing()
        .when(vault)
        .revoke("synthetic-lease");
    assertThrows(IllegalStateException.class, service::maintain);
    verify(issuances, never()).revoked(any());
    when(issuances.revoked(id)).thenReturn(true);
    service.maintain();
    verify(issuances).revoked(id);
    verify(audit).record(argThat(event -> event.type().value().equals("pam.credential.revoked")));
  }

  @Test
  void expirationIsASeparateFactBeforeVaultAcknowledgement() {
    when(issuances.pending(now))
        .thenReturn(
            List.of(
                new PrivilegedIssuances.Entry(
                    id, "REVOKING", "synthetic-lease", now, "original-pam-correlation")));
    when(issuances.recordExpiration(id, now)).thenReturn(true, false);
    doThrow(new IllegalStateException("unavailable"))
        .doNothing()
        .when(vault)
        .revoke("synthetic-lease");
    assertThrows(IllegalStateException.class, service::maintain);
    verify(audit)
        .record(
            argThat(
                event ->
                    event.type().value().equals("pam.credential.expired")
                        && event.context().correlationId().equals("original-pam-correlation")
                        && event.details().get("meaning").equals("LOGICAL_DEADLINE")));
    verify(issuances, never()).revoked(any());
    when(issuances.revoked(id)).thenReturn(true);
    service.maintain();
    verify(audit, times(1))
        .record(argThat(event -> event.type().value().equals("pam.credential.expired")));
    verify(audit)
        .record(
            argThat(
                event ->
                    event.type().value().equals("pam.credential.revoked")
                        && event.context().correlationId().equals("original-pam-correlation")
                        && event.details().get("meaning").equals("VAULT_ACKNOWLEDGED")));
  }

  @Test
  void statusNeverExposesLeaseOrCredential() {
    var request = requests.lock(id, user);
    when(requests.find(id, user)).thenReturn(request);
    when(issuances.find(id))
        .thenReturn(
            Optional.of(
                new PrivilegedIssuances.Entry(
                    id, "ISSUED", "synthetic-lease", now.plusSeconds(60))));
    assertEquals("ISSUED", service.get(id, user).state());
    assertFalse(service.get(id, user).toString().contains("synthetic-lease"));
    when(requests.find(id, user)).thenReturn(Optional.empty());
    assertThrows(ResourceNotFoundException.class, () -> service.get(id, user));
  }
}

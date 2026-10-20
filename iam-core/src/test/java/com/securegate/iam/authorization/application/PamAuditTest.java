package com.securegate.iam.authorization.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.securegate.iam.audit.application.port.*;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

class PamAuditTest {
  private final AuditTrail trail = mock(AuditTrail.class);
  private final PamAudit audit = new PamAudit(trail);

  @Test
  void unknownIdentityAndNonPamOperationsNeverBecomePamFacts() {
    audit.denied(
        null,
        "test",
        ResourceAccessService.Operation.PRIVILEGED_ISSUE,
        "invalid_token",
        PamAudit.Stage.AUTHORIZATION);
    audit.denied(
        UUID.randomUUID(),
        "test",
        ResourceAccessService.Operation.ACCOUNT_READ,
        "insufficient_scope",
        PamAudit.Stage.AUTHORIZATION);
    verifyNoInteractions(trail);
  }

  @ParameterizedTest
  @CsvSource({
    "mfa_required,MFA_REQUIRED",
    "rate_limited,RATE_LIMITED",
    "request_conflict,REQUEST_CONFLICT",
    "request_unavailable,REQUEST_UNAVAILABLE",
    "synthetic-private-response,ACCESS_DENIED"
  })
  void onlyFixedCodesAndVerifiedActorAreRecorded(String code, String reason) {
    UUID user = UUID.randomUUID();
    audit.denied(
        user,
        "pam-audit",
        ResourceAccessService.Operation.PRIVILEGED_ISSUE,
        code,
        PamAudit.Stage.CONFIRMATION);
    var capture = ArgumentCaptor.forClass(AuditRecord.class);
    verify(trail).record(capture.capture());
    var event = capture.getValue();
    assertEquals("pam.access.denied", event.type().value());
    assertEquals(user.toString(), event.context().actor());
    assertEquals("pam-audit", event.context().correlationId());
    assertEquals("database:diagnostics", event.resource());
    assertEquals(Set.of("operation", "reason", "stage"), event.details().keySet());
    assertEquals(reason, event.details().get("reason"));
  }
}

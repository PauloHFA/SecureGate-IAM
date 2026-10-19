package com.securegate.iam.authorization.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.securegate.iam.authorization.application.port.ResourceTokens;
import com.securegate.iam.identity.application.port.IdentityLookup;
import com.securegate.iam.identity.application.port.MfaProofs;
import com.securegate.iam.policy.application.port.PolicyDecisions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ResourceAccessServiceTest {
  @Test
  void distributedRequestAndPolicyDenialsCannotCreatePermitAndFailuresPropagate() {
    var enforcement =
        mock(com.securegate.iam.authorization.application.port.ResourceEnforcement.class);
    var guarded =
        new ResourceAccessService(
            tokens,
            identities,
            policies,
            proofs,
            Clock.fixed(now, ZoneOffset.UTC),
            "test-api",
            enforcement);
    assertEquals(
        "rate_limited",
        assertThrows(
                ResourceAccessService.Rejected.class,
                () ->
                    guarded.authorize(
                        "token", ResourceAccessService.Operation.ACCOUNT_READ, "127.0.0.1"))
            .code());
    when(enforcement.requestAllowed(user, 4, "127.0.0.1")).thenReturn(true);
    assertEquals(
        "insufficient_scope",
        assertThrows(
                ResourceAccessService.Rejected.class,
                () ->
                    guarded.authorize(
                        "token", ResourceAccessService.Operation.ACCOUNT_READ, "127.0.0.1"))
            .code());
    when(enforcement.policiesCurrent(anyList())).thenReturn(true);
    assertEquals(
        user,
        guarded
            .authorize("token", ResourceAccessService.Operation.ACCOUNT_READ, "127.0.0.1")
            .userId());
    when(enforcement.requestAllowed(user, 4, "127.0.0.1"))
        .thenThrow(new IllegalStateException("synthetic unavailable"));
    assertThrows(
        IllegalStateException.class,
        () ->
            guarded.authorize("token", ResourceAccessService.Operation.ACCOUNT_READ, "127.0.0.1"));
  }

  private final ResourceTokens tokens = mock(ResourceTokens.class);
  private final IdentityLookup identities = mock(IdentityLookup.class);
  private final PolicyDecisions policies = mock(PolicyDecisions.class);
  private final MfaProofs proofs = mock(MfaProofs.class);
  private final UUID user = UUID.randomUUID();
  private final UUID sid = UUID.randomUUID();
  private final Instant now = Instant.parse("2026-10-19T12:00:00Z");
  private final ResourceAccessService service =
      new ResourceAccessService(
          tokens, identities, policies, proofs, Clock.fixed(now, ZoneOffset.UTC), "test-api");

  @BeforeEach
  void setup() {
    subject(now.minusSeconds(10), now.plusSeconds(290));
    when(identities.isCurrent(user, 4)).thenReturn(true);
    decision(false, Set.of("api:read", "injected"), 60, false);
  }

  private void subject(Instant issued, Instant expires) {
    when(tokens.resolve("token", "test-api"))
        .thenReturn(
            Optional.of(
                new ResourceTokens.Subject(
                    user, 4, sid, "jti", Set.of("api:read", "api:write"), issued, expires)));
  }

  private void decision(boolean deny, Set<String> scopes, int ttl, boolean mfa) {
    when(policies.decide(eq(user), eq("account:self"), eq("read"), anySet(), eq(sid)))
        .thenReturn(new PolicyDecisions.Decision(deny, scopes, ttl, mfa, List.of(), 20));
  }

  private ResourceAccessService.Permit authorize() {
    return service.authorize("token", ResourceAccessService.Operation.ACCOUNT_READ);
  }

  @Test
  void narrowsScopesBoundsLifetimeAndReevaluatesEveryOperation() {
    var permit = authorize();
    assertEquals(user, permit.userId());
    assertEquals(Set.of("api:read"), permit.scopes());
    assertEquals(now.plusSeconds(50), permit.expiresAt());
    verifyNoInteractions(proofs);
    decision(true, Set.of("api:read"), 300, false);
    assertEquals(
        "insufficient_scope",
        assertThrows(ResourceAccessService.Rejected.class, this::authorize).code());
    verify(policies, times(2))
        .decide(user, "account:self", "read", Set.of("api:read", "api:write"), sid);
  }

  @Test
  void cannotAddRequiredScopeAndEnforcesReducedTtlAgainstTokenAge() {
    decision(false, Set.of("api:write"), 300, false);
    assertThrows(ResourceAccessService.Rejected.class, this::authorize);
    for (int ttl : new int[] {0, 29, 301}) {
      decision(false, Set.of("api:read"), ttl, false);
      assertThrows(ResourceAccessService.Rejected.class, this::authorize);
    }
    decision(false, Set.of("api:read"), 30, false);
    subject(now.minusSeconds(30), now.plusSeconds(200));
    assertThrows(ResourceAccessService.Rejected.class, this::authorize);
    subject(now.minusSeconds(10), now.plusSeconds(5));
    assertEquals(now.plusSeconds(5), authorize().expiresAt());
  }

  @Test
  void requiresCurrentIdentityAndStrictTokenTimeBounds() {
    when(identities.isCurrent(user, 4)).thenReturn(false);
    assertEquals(
        "invalid_token",
        assertThrows(ResourceAccessService.Rejected.class, this::authorize).code());
    when(identities.isCurrent(user, 4)).thenReturn(true);
    subject(now.plusSeconds(1), now.plusSeconds(300));
    assertThrows(ResourceAccessService.Rejected.class, this::authorize);
    subject(now.minusSeconds(300), now);
    assertThrows(ResourceAccessService.Rejected.class, this::authorize);
    when(tokens.resolve("token", "test-api")).thenReturn(Optional.empty());
    assertThrows(ResourceAccessService.Rejected.class, this::authorize);
    for (String token : new String[] {"", " ", "x".repeat(16385)}) {
      assertThrows(
          ResourceAccessService.Rejected.class,
          () -> service.authorize(token, ResourceAccessService.Operation.ACCOUNT_READ));
    }
  }

  @Test
  void mfaRequiresOriginalSessionProofWithLiveDeadline() {
    decision(false, Set.of("api:read"), 300, true);
    assertEquals(
        "mfa_required", assertThrows(ResourceAccessService.Rejected.class, this::authorize).code());
    when(proofs.current(user, 4, sid))
        .thenReturn(Optional.of(new MfaProofs.Proof("TOTP", now.minusSeconds(1), now)));
    assertThrows(ResourceAccessService.Rejected.class, this::authorize);
    when(proofs.current(user, 4, sid))
        .thenReturn(
            Optional.of(new MfaProofs.Proof("TOTP", now.minusSeconds(1), now.plusSeconds(15))));
    assertEquals(now.plusSeconds(15), authorize().expiresAt());
  }

  @Test
  void dependencyFailurePropagatesInsteadOfCreatingPermit() {
    when(policies.decide(any(), anyString(), anyString(), anySet(), any()))
        .thenThrow(new IllegalStateException("synthetic"));
    assertThrows(IllegalStateException.class, this::authorize);
  }
}

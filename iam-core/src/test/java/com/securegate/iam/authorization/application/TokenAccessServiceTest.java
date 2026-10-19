package com.securegate.iam.authorization.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.securegate.iam.identity.application.port.IdentityLookup;
import com.securegate.iam.identity.application.port.MfaProofs;
import com.securegate.iam.policy.application.port.PolicyDecisions;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TokenAccessServiceTest {
  private final PolicyDecisions policies = mock(PolicyDecisions.class);
  private final IdentityLookup identities = mock(IdentityLookup.class);
  private final MfaProofs proofs = mock(MfaProofs.class);
  private final UUID user = UUID.randomUUID();
  private final UUID sid = UUID.randomUUID();
  private final TokenAccessService service = new TokenAccessService(policies, identities, proofs);

  private void decision(boolean denied, Set<String> scopes, int ttl, boolean mfa) {
    when(identities.isCurrent(user, 0)).thenReturn(true);
    when(policies.decide(eq(user), eq("oauth:web-test"), eq("issue"), any(), eq(sid)))
        .thenReturn(new PolicyDecisions.Decision(denied, scopes, ttl, mfa, List.of(), 40));
  }

  @Test
  void canOnlyNarrowGrantedScopesAndUsesLiveSessionContext() {
    decision(false, Set.of("api:read", "api:admin"), 60, false);
    var permit = service.authorize(user, 0, sid, "web-test", Set.of("api:read", "api:write"));
    assertEquals(Set.of("api:read"), permit.scopes());
    assertEquals(60, permit.ttlSeconds());
    assertEquals("oauth:web-test", permit.resource());
    assertNull(permit.mfaDeadline());
    verifyNoInteractions(proofs);
  }

  @Test
  void deniesPoliciesEmptyIntersectionsAndInvalidLifetimes() {
    decision(true, Set.of("api:read"), 300, false);
    assertThrows(
        TokenAccessService.Denied.class,
        () -> service.authorize(user, 0, sid, "web-test", Set.of("api:read")));
    decision(false, Set.of("other"), 300, false);
    assertThrows(
        TokenAccessService.Denied.class,
        () -> service.authorize(user, 0, sid, "web-test", Set.of("api:read")));
    for (int ttl : new int[] {0, 29, 301}) {
      decision(false, Set.of("api:read"), ttl, false);
      assertThrows(
          TokenAccessService.Denied.class,
          () -> service.authorize(user, 0, sid, "web-test", Set.of("api:read")));
    }
  }

  @Test
  void requiresAuthoritativeMfaAndRetainsItsDeadline() {
    decision(false, Set.of("api:read"), 300, true);
    when(proofs.current(user, 0, sid)).thenReturn(Optional.empty());
    assertThrows(
        TokenAccessService.Denied.class,
        () -> service.authorize(user, 0, sid, "web-test", Set.of("api:read")));
    Instant expiry = Instant.now().plusSeconds(90);
    when(proofs.current(user, 0, sid))
        .thenReturn(Optional.of(new MfaProofs.Proof("TOTP", Instant.now(), expiry)));
    assertEquals(
        expiry, service.authorize(user, 0, sid, "web-test", Set.of("api:read")).mfaDeadline());
  }

  @Test
  void cannotTurnAnOidcGrantIntoOAuthSilently() {
    decision(false, Set.of("api:read"), 60, false);
    assertThrows(
        TokenAccessService.Denied.class,
        () -> service.authorize(user, 0, sid, "web-test", Set.of("openid", "api:read")));
  }

  @Test
  void rejectsMissingOrStaleIdentitiesAndUntrustedTargets() {
    for (String client : new String[] {null, "", "*", "UPPERCASE", "x".repeat(65)}) {
      assertThrows(
          TokenAccessService.Denied.class,
          () -> service.authorize(user, 0, sid, client, Set.of("api:read")));
    }
    assertThrows(
        TokenAccessService.Denied.class,
        () -> service.authorize(null, 0, sid, "web-test", Set.of("api:read")));
    assertThrows(
        TokenAccessService.Denied.class,
        () -> service.authorize(user, 0, null, "web-test", Set.of("api:read")));
    assertThrows(
        TokenAccessService.Denied.class, () -> service.authorize(user, 0, sid, "web-test", null));
    assertThrows(
        TokenAccessService.Denied.class,
        () -> service.authorize(user, 0, sid, "web-test", Set.of()));
    assertThrows(
        TokenAccessService.Denied.class,
        () -> service.authorize(user, 0, sid, "web-test", Set.of("api:read")));
    verifyNoInteractions(policies, proofs);
  }
}

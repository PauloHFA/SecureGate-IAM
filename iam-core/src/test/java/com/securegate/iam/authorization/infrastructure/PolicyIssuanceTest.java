package com.securegate.iam.authorization.infrastructure;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.securegate.iam.audit.application.port.AuditTrail;
import com.securegate.iam.authorization.application.TokenAccessService;
import com.securegate.iam.authorization.application.TokenAccessService.Permit;
import com.securegate.iam.authorization.application.port.ConsentRequests;
import com.securegate.iam.identity.application.port.AuthenticatedIdentity;
import com.securegate.iam.policy.application.port.PolicyDecisions;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.token.DefaultOAuth2TokenContext;

class PolicyIssuanceTest {
  private final TokenAccessService access = mock(TokenAccessService.class);
  private final PolicyIssuance issuance = new PolicyIssuance(access);
  private final UUID user = UUID.randomUUID();
  private final UUID sid = UUID.randomUUID();
  private final RegisteredClient client =
      RegisteredClient.withId("id")
          .clientId("web-test")
          .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
          .redirectUri("https://client.example/callback")
          .scope("api:read")
          .scope("api:write")
          .build();
  private final AuthenticatedIdentity identity =
      new AuthenticatedIdentity() {
        public String getName() {
          return user.toString();
        }

        public long version() {
          return 0;
        }
      };

  private DefaultOAuth2TokenContext context(AuthorizationGrantType grant) {
    var principal = UsernamePasswordAuthenticationToken.authenticated(identity, null, List.of());
    var authorization =
        OAuth2Authorization.withRegisteredClient(client)
            .principalName(user.toString())
            .authorizationGrantType(grant)
            .authorizedScopes(Set.of("api:read", "api:write"))
            .attribute(ConsentRequests.SESSION_ID, sid.toString())
            .build();
    return DefaultOAuth2TokenContext.builder()
        .registeredClient(client)
        .principal(principal)
        .authorization(authorization)
        .authorizedScopes(Set.of("api:read", "api:write"))
        .authorizationGrantType(grant)
        .tokenType(OAuth2TokenType.ACCESS_TOKEN)
        .build();
  }

  private void permit(Instant deadline) {
    when(access.authorize(eq(user), eq(0L), eq(sid), eq("web-test"), anySet()))
        .thenReturn(
            new Permit(
                user,
                "oauth:web-test",
                Set.of("api:read"),
                60,
                deadline,
                new PolicyDecisions.Decision(
                    false, Set.of("api:read"), 60, deadline != null, List.of(), 40)));
  }

  @Test
  void pinsOneDecisionAndDoesNotMutateTheRegisteredClientOrOriginalContext() {
    permit(null);
    issuance.open();
    try {
      var original = context(AuthorizationGrantType.AUTHORIZATION_CODE);
      var constrained = issuance.constrain(original);
      assertEquals(Set.of("api:read"), constrained.getAuthorizedScopes());
      assertEquals(
          Duration.ofSeconds(60),
          constrained.getRegisteredClient().getTokenSettings().getAccessTokenTimeToLive());
      assertEquals(Set.of("api:read", "api:write"), original.getAuthorizedScopes());
      assertEquals(Duration.ofMinutes(5), client.getTokenSettings().getAccessTokenTimeToLive());
      issuance.constrain(original);
      verify(access, times(1)).authorize(any(), anyLong(), any(), any(), any());
    } finally {
      issuance.close();
    }
    assertNull(issuance.permit());
  }

  @Test
  void capsAccessExpiryToMfaDeadlineAndRejectsAnExpiredProof() {
    Instant deadline = Instant.now().plusSeconds(30);
    permit(deadline);
    issuance.open();
    try {
      issuance.constrain(context(AuthorizationGrantType.AUTHORIZATION_CODE));
      var claims = JwtClaimsSet.builder().expiresAt(Instant.now().plusSeconds(300));
      issuance.boundExpiry(claims);
      assertEquals(deadline, claims.build().getExpiresAt());
    } finally {
      issuance.close();
    }
    permit(Instant.now().minusSeconds(1));
    issuance.open();
    try {
      issuance.constrain(context(AuthorizationGrantType.REFRESH_TOKEN));
      assertThrows(
          PolicyGrantException.class,
          () ->
              issuance.boundExpiry(
                  JwtClaimsSet.builder().expiresAt(Instant.now().plusSeconds(300))));
    } finally {
      issuance.close();
    }
  }

  @Test
  void userGenerationWithoutProviderScopeFailsClosedWhileMachineGrantIsUnchanged() {
    assertThrows(
        PolicyGrantException.class,
        () -> issuance.constrain(context(AuthorizationGrantType.AUTHORIZATION_CODE)));
    var machine = context(AuthorizationGrantType.CLIENT_CREDENTIALS);
    assertSame(machine, issuance.constrain(machine));
    verifyNoInteractions(access);
  }

  @Test
  void providerAlwaysClearsItsThreadScopeAfterFailure() {
    var delegate = mock(AuthenticationProvider.class);
    var request = UsernamePasswordAuthenticationToken.unauthenticated("client", "unused");
    when(delegate.authenticate(request)).thenThrow(new IllegalStateException("synthetic"));
    var provider =
        new PolicyGrantProvider(
            delegate,
            issuance,
            mock(OAuth2AuthorizationService.class),
            mock(AuditTrail.class),
            () -> "test");
    var failure = assertThrows(PolicyGrantException.class, () -> provider.authenticate(request));
    assertEquals("temporarily_unavailable", failure.getError().getErrorCode());
    assertNull(issuance.permit());
    issuance.open();
    issuance.close();
  }
}

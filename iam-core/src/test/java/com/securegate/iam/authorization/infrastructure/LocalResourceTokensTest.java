package com.securegate.iam.authorization.infrastructure;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;

class LocalResourceTokensTest {
  @Test
  void distributedTokenCheckUsesStoredGrantAndOnlyRunsAfterLocalValidation() {
    var enforcement =
        mock(com.securegate.iam.authorization.application.port.ResourceEnforcement.class);
    var guarded = new LocalResourceTokens(decoder, authorizations, ledger, enforcement);
    var user = java.util.UUID.randomUUID();
    var sid = java.util.UUID.randomUUID();
    var now = java.time.Instant.now();
    when(decoder.decode("token"))
        .thenReturn(
            Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(user.toString())
                .claim("jti", "synthetic-jti")
                .audience(List.of("api"))
                .claim("sid", sid.toString())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(60))
                .build());
    var authorization = mock(OAuth2Authorization.class, RETURNS_DEEP_STUBS);
    when(authorizations.findByToken("token", OAuth2TokenType.ACCESS_TOKEN))
        .thenReturn(authorization);
    when(authorization.getAccessToken().getToken().getTokenValue()).thenReturn("token");
    when(authorization.getAccessToken().getToken().getScopes())
        .thenReturn(java.util.Set.of("api:read"));
    when(authorization.getAccessToken().isActive()).thenReturn(true);
    when(authorization.getAuthorizationGrantType())
        .thenReturn(AuthorizationGrantType.AUTHORIZATION_CODE);
    when(authorization.getId()).thenReturn("stored-grant");
    var identity =
        new com.securegate.iam.identity.application.port.AuthenticatedIdentity() {
          public String getName() {
            return user.toString();
          }

          public long version() {
            return 1;
          }
        };
    doReturn(
            org.springframework.security.authentication.UsernamePasswordAuthenticationToken
                .authenticated(identity, null, List.of()))
        .when(authorization)
        .getAttribute(java.security.Principal.class.getName());
    doReturn(sid.toString())
        .when(authorization)
        .getAttribute(com.securegate.iam.authorization.application.port.ConsentRequests.SESSION_ID);
    assertTrue(guarded.resolve("token", "api").isEmpty());
    when(enforcement.tokenAllowed(user, 1, sid, "synthetic-jti", "stored-grant")).thenReturn(true);
    assertTrue(guarded.resolve("token", "api").isPresent());
    when(enforcement.tokenAllowed(user, 1, sid, "synthetic-jti", "stored-grant"))
        .thenThrow(new RedisResourceEnforcement.Unavailable());
    assertThrows(RedisResourceEnforcement.Unavailable.class, () -> guarded.resolve("token", "api"));
    assertTrue(
        com.securegate.iam.authorization.application.port.ResourceEnforcement.DISABLED.tokenAllowed(
            user, 1, sid, "synthetic-jti", "stored-grant"));
  }

  private final JwtDecoder decoder = mock(JwtDecoder.class);
  private final OAuth2AuthorizationService authorizations = mock(OAuth2AuthorizationService.class);
  private final RefreshTokenLedger ledger = mock(RefreshTokenLedger.class);
  private final LocalResourceTokens tokens =
      new LocalResourceTokens(decoder, authorizations, ledger);

  @Test
  void invalidSignatureAndForeignAudienceNeverConsultGrantStorage() {
    when(decoder.decode("bad")).thenThrow(new JwtException("invalid signature"));
    assertTrue(tokens.resolve("bad", "api").isEmpty());
    when(decoder.decode("foreign"))
        .thenReturn(
            Jwt.withTokenValue("foreign")
                .header("alg", "RS256")
                .claim("aud", List.of("other"))
                .build());
    assertTrue(tokens.resolve("foreign", "api").isEmpty());
    verifyNoInteractions(authorizations, ledger);
  }

  @Test
  void machineGrantCannotBecomeHumanResourceSubject() {
    when(decoder.decode("machine"))
        .thenReturn(
            Jwt.withTokenValue("machine")
                .header("alg", "RS256")
                .claim("aud", List.of("api"))
                .build());
    var authorization = mock(OAuth2Authorization.class, RETURNS_DEEP_STUBS);
    when(authorizations.findByToken("machine", OAuth2TokenType.ACCESS_TOKEN))
        .thenReturn(authorization);
    when(authorization.getAccessToken().getToken().getTokenValue()).thenReturn("machine");
    when(authorization.getAccessToken().isActive()).thenReturn(true);
    when(authorization.getAuthorizationGrantType())
        .thenReturn(AuthorizationGrantType.CLIENT_CREDENTIALS);
    assertTrue(tokens.resolve("machine", "api").isEmpty());
    verifyNoInteractions(ledger);
  }

  @Test
  void storageFailureIsNotTurnedIntoSuccessfulOfflineValidation() {
    when(decoder.decode("token"))
        .thenReturn(
            Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .claim("aud", List.of("api"))
                .build());
    when(authorizations.findByToken(anyString(), any()))
        .thenThrow(new IllegalStateException("offline"));
    assertThrows(IllegalStateException.class, () -> tokens.resolve("token", "api"));
  }
}

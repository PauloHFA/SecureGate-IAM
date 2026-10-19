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

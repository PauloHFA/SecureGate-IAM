package com.securegate.iam.authorization.infrastructure;

import com.securegate.iam.authorization.application.port.ConsentRequests;
import com.securegate.iam.authorization.application.port.ResourceTokens;
import com.securegate.iam.identity.application.port.AuthenticatedIdentity;
import java.security.Principal;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;

/** Online local grant check; stage 7 may add distributed revocation behind ResourceTokens. */
public final class LocalResourceTokens implements ResourceTokens {
  private final JwtDecoder decoder;
  private final OAuth2AuthorizationService authorizations;
  private final RefreshTokenLedger ledger;

  public LocalResourceTokens(
      JwtDecoder decoder, OAuth2AuthorizationService authorizations, RefreshTokenLedger ledger) {
    this.decoder = decoder;
    this.authorizations = authorizations;
    this.ledger = ledger;
  }

  @Override
  public Optional<Subject> resolve(String token, String audience) {
    Jwt jwt;
    try {
      jwt = decoder.decode(token);
    } catch (JwtException invalid) {
      return Optional.empty();
    }
    if (!jwt.getAudience().contains(audience)) {
      return Optional.empty();
    }
    var authorization = authorizations.findByToken(token, OAuth2TokenType.ACCESS_TOKEN);
    if (authorization == null
        || authorization.getAccessToken() == null
        || !authorization.getAccessToken().getToken().getTokenValue().equals(token)
        || !authorization.getAccessToken().isActive()
        || !AuthorizationGrantType.AUTHORIZATION_CODE.equals(
            authorization.getAuthorizationGrantType())
        || ledger.revoked(authorization.getId())) {
      return Optional.empty();
    }
    Authentication principal = authorization.getAttribute(Principal.class.getName());
    if (principal == null
        || !(principal.getPrincipal() instanceof AuthenticatedIdentity identity)) {
      return Optional.empty();
    }
    String sid = authorization.getAttribute(ConsentRequests.SESSION_ID);
    if (!identity.getName().equals(jwt.getSubject())
        || sid == null
        || !sid.equals(jwt.getClaimAsString("sid"))) {
      return Optional.empty();
    }
    try {
      // Persisted scopes are authoritative and bound to the exact token value above.
      return Optional.of(
          new Subject(
              UUID.fromString(identity.getName()),
              identity.version(),
              UUID.fromString(sid),
              jwt.getId(),
              authorization.getAccessToken().getToken().getScopes(),
              jwt.getIssuedAt(),
              jwt.getExpiresAt()));
    } catch (IllegalArgumentException invalid) {
      return Optional.empty();
    }
  }
}

package com.securegate.iam.authorization.infrastructure;

import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.server.authorization.token.OAuth2RefreshTokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;

public final class FamilyBoundRefreshGenerator implements OAuth2TokenGenerator<OAuth2RefreshToken> {
  private final OAuth2RefreshTokenGenerator delegate = new OAuth2RefreshTokenGenerator();
  private final RefreshTokenLedger ledger;

  public FamilyBoundRefreshGenerator(RefreshTokenLedger ledger) {
    this.ledger = ledger;
  }

  @Override
  public OAuth2RefreshToken generate(OAuth2TokenContext context) {
    var token = delegate.generate(context);
    if (token == null || context.getAuthorization() == null) {
      return token;
    }
    var deadline = ledger.expires(context.getAuthorization().getId()).orElse(token.getExpiresAt());
    if (!deadline.isAfter(token.getIssuedAt())) {
      throw new OAuth2AuthenticationException("invalid_grant");
    }
    return new OAuth2RefreshToken(
        token.getTokenValue(),
        token.getIssuedAt(),
        deadline.isBefore(token.getExpiresAt()) ? deadline : token.getExpiresAt());
  }
}

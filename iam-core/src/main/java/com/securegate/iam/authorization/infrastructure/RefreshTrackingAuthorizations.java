package com.securegate.iam.authorization.infrastructure;

import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;

public final class RefreshTrackingAuthorizations implements OAuth2AuthorizationService {
  private final OAuth2AuthorizationService delegate;
  private final RefreshTokenLedger ledger;

  public RefreshTrackingAuthorizations(
      OAuth2AuthorizationService delegate, RefreshTokenLedger ledger) {
    this.delegate = delegate;
    this.ledger = ledger;
  }

  @Override
  public void save(OAuth2Authorization authorization) {
    delegate.save(authorization);
    ledger.record(authorization);
  }

  @Override
  public void remove(OAuth2Authorization authorization) {
    delegate.remove(authorization);
  }

  @Override
  public OAuth2Authorization findById(String id) {
    return delegate.findById(id);
  }

  @Override
  public OAuth2Authorization findByToken(String token, OAuth2TokenType type) {
    return delegate.findByToken(token, type);
  }
}

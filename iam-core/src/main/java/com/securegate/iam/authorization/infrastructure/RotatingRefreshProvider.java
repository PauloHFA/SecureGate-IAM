package com.securegate.iam.authorization.infrastructure;

import java.time.Instant;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2RefreshTokenAuthenticationToken;
import org.springframework.transaction.support.TransactionTemplate;

public final class RotatingRefreshProvider implements AuthenticationProvider {
  private final AuthenticationProvider delegate;
  private final RefreshTokenLedger ledger;
  private final OAuth2AuthorizationService authorizations;
  private final TransactionTemplate transactions;

  public RotatingRefreshProvider(
      AuthenticationProvider delegate,
      RefreshTokenLedger ledger,
      OAuth2AuthorizationService authorizations,
      TransactionTemplate transactions) {
    this.delegate = delegate;
    this.ledger = ledger;
    this.authorizations = authorizations;
    this.transactions = transactions;
  }

  @Override
  public Authentication authenticate(Authentication authentication) {
    var request = (OAuth2RefreshTokenAuthenticationToken) authentication;
    if (!(request.getPrincipal() instanceof OAuth2ClientAuthenticationToken client)
        || !client.isAuthenticated()) {
      throw new OAuth2AuthenticationException("invalid_client");
    }
    var result =
        transactions.execute(
            status -> {
              var entry = ledger.lockToken(request.getRefreshToken()).orElse(null);
              if (entry == null || !entry.clientId().equals(client.getRegisteredClient().getId())) {
                return invalid();
              }
              if (entry.revoked()) {
                return invalid();
              }
              if (entry.consumed()) {
                ledger.revoke(entry.authorizationId(), authorizations, "oauth.refresh.reused");
                return invalid();
              }
              Instant now = Instant.now();
              if (!entry.familyExpires().isAfter(now) || !entry.tokenExpires().isAfter(now)) {
                return invalid();
              }
              ledger.consume(request.getRefreshToken());
              try {
                return new Result(delegate.authenticate(authentication), null);
              } catch (AuthenticationException failure) {
                status.setRollbackOnly();
                return new Result(null, failure);
              }
            });
    if (result.failure() != null) {
      throw result.failure();
    }
    return result.authentication();
  }

  private static Result invalid() {
    return new Result(null, new OAuth2AuthenticationException("invalid_grant"));
  }

  @Override
  public boolean supports(Class<?> type) {
    return delegate.supports(type);
  }

  private record Result(Authentication authentication, AuthenticationException failure) {}
}

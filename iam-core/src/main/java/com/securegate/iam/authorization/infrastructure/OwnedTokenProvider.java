package com.securegate.iam.authorization.infrastructure;

import com.securegate.iam.identity.application.port.AuthenticatedIdentity;
import com.securegate.iam.identity.application.port.IdentityLookup;
import java.security.Principal;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenIntrospection;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2TokenIntrospectionAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2TokenRevocationAuthenticationToken;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Client ownership is required even for introspection; unknown/foreign tokens reveal no metadata.
 */
public final class OwnedTokenProvider implements AuthenticationProvider {
  private final AuthenticationProvider delegate;
  private final OAuth2AuthorizationService authorizations;
  private final IdentityLookup identities;
  private final RefreshTokenLedger ledger;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;

  public OwnedTokenProvider(
      AuthenticationProvider delegate,
      OAuth2AuthorizationService authorizations,
      IdentityLookup identities,
      RefreshTokenLedger ledger,
      JdbcTemplate jdbc,
      TransactionTemplate transactions) {
    this.delegate = delegate;
    this.authorizations = authorizations;
    this.identities = identities;
    this.ledger = ledger;
    this.jdbc = jdbc;
    this.transactions = transactions;
  }

  @Override
  public Authentication authenticate(Authentication authentication) {
    if (!(authentication.getPrincipal() instanceof OAuth2ClientAuthenticationToken client)
        || !client.isAuthenticated()) {
      throw new OAuth2AuthenticationException("invalid_client");
    }
    if (authentication instanceof OAuth2TokenIntrospectionAuthenticationToken request) {
      var authorization = authorizations.findByToken(request.getToken(), null);
      if (!owns(authorization, client)
          || !isAccessOrRefresh(authorization, request.getToken())
          || ledger.revoked(authorization.getId())
          || !currentIdentity(authorization)) {
        return new OAuth2TokenIntrospectionAuthenticationToken(
            request.getToken(), client, OAuth2TokenIntrospection.builder().build());
      }
      return delegate.authenticate(authentication);
    }
    var request = (OAuth2TokenRevocationAuthenticationToken) authentication;
    return transactions.execute(
        status -> {
          var historical = ledger.lockToken(request.getToken()).orElse(null);
          if (historical != null) {
            if (historical.clientId().equals(client.getRegisteredClient().getId())) {
              ledger.revoke(
                  historical.authorizationId(), authorizations, "oauth.authorization.revoked");
            }
            return authentication;
          }
          var authorization = authorizations.findByToken(request.getToken(), null);
          if (!owns(authorization, client)
              || !isAccessOrRefresh(authorization, request.getToken())) {
            return authentication;
          }
          if (ledger.lockFamily(authorization.getId())) {
            ledger.revoke(authorization.getId(), authorizations, "oauth.authorization.revoked");
            return authentication;
          }
          jdbc.queryForList(
              "SELECT id FROM iam_authorization.oauth2_authorization WHERE id=? FOR UPDATE",
              String.class,
              authorization.getId());
          authorization = authorizations.findById(authorization.getId());
          boolean active = authorization.getToken(request.getToken()).isActive();
          var result = delegate.authenticate(authentication);
          if (active) {
            ledger.audit(authorization, "oauth.authorization.revoked");
          }
          return result;
        });
  }

  private boolean currentIdentity(OAuth2Authorization authorization) {
    if (!AuthorizationGrantType.AUTHORIZATION_CODE.equals(
        authorization.getAuthorizationGrantType())) {
      return true;
    }
    Authentication principal = authorization.getAttribute(Principal.class.getName());
    return principal != null
        && principal.getPrincipal() instanceof AuthenticatedIdentity identity
        && identities.isCurrent(UUID.fromString(identity.getName()), identity.version());
  }

  private static boolean owns(
      OAuth2Authorization authorization, OAuth2ClientAuthenticationToken client) {
    return authorization != null
        && authorization.getRegisteredClientId().equals(client.getRegisteredClient().getId());
  }

  private static boolean isAccessOrRefresh(OAuth2Authorization authorization, String value) {
    return (authorization.getAccessToken() != null
            && authorization.getAccessToken().getToken().getTokenValue().equals(value))
        || (authorization.getRefreshToken() != null
            && authorization.getRefreshToken().getToken().getTokenValue().equals(value));
  }

  @Override
  public boolean supports(Class<?> type) {
    return delegate.supports(type);
  }
}

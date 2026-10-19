package com.securegate.iam.authorization.infrastructure;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeAuthenticationToken;
import org.springframework.transaction.support.TransactionTemplate;

/** The upstream JDBC service does not make a provider's read/check/write atomic by itself. */
public final class SerializedCodeRedemption implements AuthenticationProvider {
  private final AuthenticationProvider delegate;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;

  public SerializedCodeRedemption(
      AuthenticationProvider delegate, JdbcTemplate jdbc, TransactionTemplate transactions) {
    this.delegate = delegate;
    this.jdbc = jdbc;
    this.transactions = transactions;
  }

  @Override
  public Authentication authenticate(Authentication authentication) {
    var result =
        transactions.execute(
            status -> {
              var code = (OAuth2AuthorizationCodeAuthenticationToken) authentication;
              jdbc.queryForList(
                  "SELECT id FROM iam_authorization.oauth2_authorization WHERE authorization_code_value=? FOR UPDATE",
                  String.class,
                  code.getCode());
              try {
                return new Result(delegate.authenticate(authentication), null);
              } catch (AuthenticationException failure) {
                if (failure instanceof PolicyGrantException) {
                  status.setRollbackOnly();
                }
                // Commit the provider's invalidation on replay, then propagate the OAuth error.
                return new Result(null, failure);
              }
            });
    if (result.failure() != null) {
      throw result.failure();
    }
    return result.authentication();
  }

  @Override
  public boolean supports(Class<?> type) {
    return delegate.supports(type);
  }

  private record Result(Authentication authentication, AuthenticationException failure) {}
}

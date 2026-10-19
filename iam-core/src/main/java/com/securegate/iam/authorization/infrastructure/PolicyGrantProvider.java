package com.securegate.iam.authorization.infrastructure;

import com.securegate.events.SecurityEventType;
import com.securegate.iam.audit.application.port.AuditRecord;
import com.securegate.iam.audit.application.port.AuditTrail;
import com.securegate.iam.shared.application.port.OperationContext;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AccessTokenAuthenticationToken;

/** Runs INSIDE code/family transactions, after the upstream provider validates client/grant. */
public final class PolicyGrantProvider implements AuthenticationProvider {
  private static final org.slf4j.Logger LOG =
      org.slf4j.LoggerFactory.getLogger(PolicyGrantProvider.class);
  private final AuthenticationProvider delegate;
  private final PolicyIssuance issuance;
  private final OAuth2AuthorizationService authorizations;
  private final AuditTrail audit;
  private final Supplier<String> correlation;

  public PolicyGrantProvider(
      AuthenticationProvider delegate,
      PolicyIssuance issuance,
      OAuth2AuthorizationService authorizations,
      AuditTrail audit,
      Supplier<String> correlation) {
    this.delegate = delegate;
    this.issuance = issuance;
    this.authorizations = authorizations;
    this.audit = audit;
    this.correlation = correlation;
  }

  @Override
  public Authentication authenticate(Authentication request) {
    issuance.open();
    try {
      var authenticated = delegate.authenticate(request);
      if (!(authenticated instanceof OAuth2AccessTokenAuthenticationToken result)
          || issuance.permit() == null) {
        throw new PolicyGrantException("server_error");
      }
      var permit = issuance.permit();
      var original = result.getAccessToken();
      var access =
          new OAuth2AccessToken(
              original.getTokenType(),
              original.getTokenValue(),
              original.getIssuedAt(),
              original.getExpiresAt(),
              permit.scopes());
      var saved = authorizations.findByToken(access.getTokenValue(), OAuth2TokenType.ACCESS_TOKEN);
      if (saved == null) {
        throw new PolicyGrantException("server_error");
      }
      authorizations.save(
          OAuth2Authorization.from(saved)
              .authorizedScopes(permit.scopes())
              .token(access, metadata -> metadata.putAll(saved.getAccessToken().getMetadata()))
              .build());
      audit.record(
          new AuditRecord(
              new SecurityEventType("oauth.policy.applied"),
              permit.resource(),
              new OperationContext(permit.userId().toString(), correlation.get()),
              Map.of(
                  "policies",
                  permit.decision().policies().stream()
                      .map(policy -> policy.id() + ":" + policy.version())
                      .collect(Collectors.joining(",")),
                  "riskScore",
                  Integer.toString(permit.decision().riskScore()),
                  "mfa",
                  Boolean.toString(permit.decision().mfaRequired()),
                  "scope",
                  permit.scopes().stream().sorted().collect(Collectors.joining(" ")),
                  "grant",
                  saved.getAuthorizationGrantType().getValue())));
      return new OAuth2AccessTokenAuthenticationToken(
          result.getRegisteredClient(),
          (Authentication) result.getPrincipal(),
          access,
          result.getRefreshToken(),
          result.getAdditionalParameters());
    } catch (OAuth2AuthenticationException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      LOG.warn(
          "Policy issuance unavailable (type={}, at={})",
          failure.getClass().getSimpleName(),
          failure.getStackTrace().length == 0 ? "unknown" : failure.getStackTrace()[0]);
      throw new PolicyGrantException("temporarily_unavailable");
    } finally {
      issuance.close();
    }
  }

  @Override
  public boolean supports(Class<?> type) {
    return delegate.supports(type);
  }
}

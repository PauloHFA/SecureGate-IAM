package com.securegate.iam.authorization.infrastructure;

import java.util.function.Consumer;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationContext;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationException;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationValidator;

public final class StrictAuthorizationRequest
    implements Consumer<OAuth2AuthorizationCodeRequestAuthenticationContext> {
  @Override
  public void accept(OAuth2AuthorizationCodeRequestAuthenticationContext context) {
    OAuth2AuthorizationCodeRequestAuthenticationToken request = context.getAuthentication();
    var parameters = request.getAdditionalParameters();
    Object challenge = parameters.get("code_challenge");
    if (request.getRedirectUri() == null
        || !context.getRegisteredClient().getRedirectUris().contains(request.getRedirectUri())
        || !"S256".equals(parameters.get("code_challenge_method"))
        || !(challenge instanceof String text)
        || !text.matches("[A-Za-z0-9_-]{43}")
        || request.getState() == null
        || !request.getState().matches("[A-Za-z0-9._~-]{16,128}")
        || request.getScopes().isEmpty()) {
      // A null authentication result prevents redirecting to any unvalidated URI.
      throw new OAuth2AuthorizationCodeRequestAuthenticationException(
          new OAuth2Error("invalid_request"), null);
    }
    OAuth2AuthorizationCodeRequestAuthenticationValidator.DEFAULT_SCOPE_VALIDATOR.accept(context);
  }
}

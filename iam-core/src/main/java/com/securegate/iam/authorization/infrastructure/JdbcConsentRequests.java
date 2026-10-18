package com.securegate.iam.authorization.infrastructure;

import com.securegate.iam.authorization.application.port.ConsentRequests;
import java.time.Instant;
import java.util.Optional;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

public final class JdbcConsentRequests implements ConsentRequests {
  private final OAuth2AuthorizationService authorizations;
  private final RegisteredClientRepository clients;

  public JdbcConsentRequests(
      OAuth2AuthorizationService authorizations, RegisteredClientRepository clients) {
    this.authorizations = authorizations;
    this.clients = clients;
  }

  @Override
  public Optional<ConsentView> find(String state, String clientId, String userId, String sid) {
    if (state == null || state.length() > 256 || sid == null) {
      return Optional.empty();
    }
    var authorization = authorizations.findByToken(state, new OAuth2TokenType("state"));
    if (authorization == null
        || !authorization.getPrincipalName().equals(userId)
        || !sid.equals(authorization.getAttribute(SESSION_ID))
        || authorization.getToken(OAuth2AuthorizationCode.class) != null) {
      return Optional.empty();
    }
    Object expiry = authorization.getAttribute(EXPIRES);
    if (!(expiry instanceof String text)) {
      return Optional.empty();
    }
    try {
      if (!Instant.parse(text).isAfter(Instant.now())) {
        return Optional.empty();
      }
    } catch (java.time.format.DateTimeParseException exception) {
      return Optional.empty();
    }
    var client = clients.findById(authorization.getRegisteredClientId());
    if (client == null || !client.getClientId().equals(clientId)) {
      return Optional.empty();
    }
    OAuth2AuthorizationRequest request =
        authorization.getAttribute(OAuth2AuthorizationRequest.class.getName());
    if (request == null) {
      return Optional.empty();
    }
    return Optional.of(
        new ConsentView(
            state,
            clientId,
            client.getClientName(),
            request.getScopes().stream().sorted().toList()));
  }
}

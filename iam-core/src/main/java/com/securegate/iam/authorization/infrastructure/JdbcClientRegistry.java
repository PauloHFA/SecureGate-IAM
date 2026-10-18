package com.securegate.iam.authorization.infrastructure;

import com.securegate.iam.authorization.application.ClientView;
import com.securegate.iam.authorization.application.port.ClientRegistry;
import com.securegate.iam.authorization.domain.BrowserClient;
import com.securegate.iam.authorization.domain.MachineClient;
import com.securegate.iam.shared.application.ConcurrentUpdateException;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;

public final class JdbcClientRegistry implements ClientRegistry {
  public static final String AUDIENCE = "securegate.audience";
  private final RegisteredClientRepository clients;

  public JdbcClientRegistry(RegisteredClientRepository clients) {
    this.clients = clients;
  }

  @Override
  public ClientView create(MachineClient definition, String secretHash) {
    var client =
        RegisteredClient.withId(UUID.randomUUID().toString())
            .clientId(definition.clientId())
            .clientName(definition.name())
            .clientSecret("{argon2}" + secretHash)
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
            .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
            .scopes(scopes -> scopes.addAll(definition.scopes()))
            .clientSettings(
                ClientSettings.builder().setting(AUDIENCE, definition.audience()).build())
            .tokenSettings(
                TokenSettings.builder().accessTokenTimeToLive(Duration.ofMinutes(5)).build())
            .build();
    return save(client);
  }

  @Override
  public ClientView createBrowser(BrowserClient definition, String secretHash) {
    var client =
        RegisteredClient.withId(UUID.randomUUID().toString())
            .clientId(definition.definition().clientId())
            .clientName(definition.definition().name())
            .clientSecret("{argon2}" + secretHash)
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .authorizationGrantTypes(
                grants -> {
                  if (definition.refreshTokens()) {
                    grants.add(AuthorizationGrantType.REFRESH_TOKEN);
                  }
                })
            .redirectUris(uris -> uris.addAll(definition.redirectUris()))
            .scopes(scopes -> scopes.addAll(definition.definition().scopes()))
            .clientSettings(
                ClientSettings.builder()
                    .requireProofKey(true)
                    .requireAuthorizationConsent(true)
                    .setting(AUDIENCE, definition.definition().audience())
                    .build())
            .tokenSettings(
                TokenSettings.builder()
                    .accessTokenTimeToLive(Duration.ofMinutes(5))
                    .authorizationCodeTimeToLive(Duration.ofMinutes(2))
                    .reuseRefreshTokens(false)
                    .refreshTokenTimeToLive(Duration.ofMinutes(30))
                    .build())
            .build();
    return save(client);
  }

  private ClientView save(RegisteredClient client) {
    try {
      clients.save(client);
    } catch (IllegalArgumentException exception) {
      // Upstream also checks uniqueness in Java; a concurrent registration may win that race.
      if (clients.findByClientId(client.getClientId()) != null) {
        throw new ConcurrentUpdateException();
      }
      throw exception;
    }
    return view(client);
  }

  @Override
  public Optional<ClientView> find(String clientId) {
    return Optional.ofNullable(clients.findByClientId(clientId)).map(this::view);
  }

  private ClientView view(RegisteredClient client) {
    return new ClientView(
        client.getClientId(),
        client.getClientName(),
        client.getClientSettings().getSetting(AUDIENCE),
        client.getScopes(),
        client.getTokenSettings().getAccessTokenTimeToLive().toSeconds(),
        client.getAuthorizationGrantTypes().contains(AuthorizationGrantType.REFRESH_TOKEN));
  }
}

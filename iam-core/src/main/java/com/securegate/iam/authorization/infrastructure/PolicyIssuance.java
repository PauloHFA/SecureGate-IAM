package com.securegate.iam.authorization.infrastructure;

import com.securegate.iam.authorization.application.TokenAccessService;
import com.securegate.iam.authorization.application.TokenAccessService.Permit;
import com.securegate.iam.authorization.application.port.ConsentRequests;
import com.securegate.iam.identity.application.port.AuthenticatedIdentity;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenContext;

/** One decision per grant execution, discarded in finally, never stored in a browser session. */
public final class PolicyIssuance {
  private static final org.slf4j.Logger LOG =
      org.slf4j.LoggerFactory.getLogger(PolicyIssuance.class);
  private final ThreadLocal<State> current = new ThreadLocal<>();
  private final TokenAccessService access;

  public PolicyIssuance(TokenAccessService access) {
    this.access = access;
  }

  public void open() {
    if (current.get() != null) {
      throw new PolicyGrantException("server_error");
    }
    current.set(new State());
  }

  public void close() {
    current.remove();
  }

  public Permit permit() {
    return current.get() == null ? null : current.get().permit;
  }

  public OAuth2TokenContext constrain(OAuth2TokenContext context) {
    if (!AuthorizationGrantType.AUTHORIZATION_CODE.equals(context.getAuthorizationGrantType())
        && !AuthorizationGrantType.REFRESH_TOKEN.equals(context.getAuthorizationGrantType())) {
      return context;
    }
    State state = current.get();
    if (state == null) {
      throw new PolicyGrantException("server_error");
    }
    if (state.permit == null) {
      try {
        if (!(context.getPrincipal().getPrincipal() instanceof AuthenticatedIdentity identity)
            || context.getAuthorization() == null) {
          throw new TokenAccessService.Denied();
        }
        String sid = context.getAuthorization().getAttribute(ConsentRequests.SESSION_ID);
        state.permit =
            access.authorize(
                UUID.fromString(identity.getName()),
                identity.version(),
                UUID.fromString(sid),
                context.getRegisteredClient().getClientId(),
                context.getAuthorizedScopes());
      } catch (TokenAccessService.Denied exception) {
        throw new PolicyGrantException("invalid_grant");
      } catch (RuntimeException exception) {
        LOG.warn(
            "Policy evaluation unavailable (type={}, at={})",
            exception.getClass().getSimpleName(),
            exception.getStackTrace().length == 0 ? "unknown" : exception.getStackTrace()[0]);
        throw new PolicyGrantException("temporarily_unavailable");
      }
    }
    Permit permit = state.permit;
    Duration configured =
        context.getRegisteredClient().getTokenSettings().getAccessTokenTimeToLive();
    Duration ttl =
        configured.compareTo(Duration.ofSeconds(permit.ttlSeconds())) < 0
            ? configured
            : Duration.ofSeconds(permit.ttlSeconds());
    var client =
        RegisteredClient.from(context.getRegisteredClient())
            .tokenSettings(
                TokenSettings.withSettings(
                        context.getRegisteredClient().getTokenSettings().getSettings())
                    .accessTokenTimeToLive(ttl)
                    .build())
            .build();
    return new OAuth2TokenContext() {
      @Override
      public <V> V get(Object key) {
        return context.get(key);
      }

      @Override
      public boolean hasKey(Object key) {
        return context.hasKey(key);
      }

      @Override
      public Set<String> getAuthorizedScopes() {
        return permit.scopes();
      }

      @Override
      public RegisteredClient getRegisteredClient() {
        return client;
      }
    };
  }

  public void boundExpiry(JwtClaimsSet.Builder claims) {
    var permit = permit();
    if (permit == null || permit.mfaDeadline() == null) {
      return;
    }
    Instant expiry = claims.build().getExpiresAt();
    Instant deadline = permit.mfaDeadline();
    if (!deadline.isAfter(Instant.now().plusSeconds(1))) {
      throw new PolicyGrantException("invalid_grant");
    }
    if (expiry.isAfter(deadline)) {
      claims.expiresAt(deadline);
    }
  }

  private static final class State {
    private Permit permit;
  }
}

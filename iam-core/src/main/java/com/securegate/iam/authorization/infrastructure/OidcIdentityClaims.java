package com.securegate.iam.authorization.infrastructure;

import com.securegate.iam.identity.application.port.AuthenticatedIdentity;
import com.securegate.iam.identity.application.port.IdentityProfiles;
import java.security.Principal;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;

public final class OidcIdentityClaims {
  public static final String AUTH_TIME = "securegate.auth.time";
  private final IdentityProfiles profiles;

  public OidcIdentityClaims(IdentityProfiles profiles) {
    this.profiles = profiles;
  }

  public Map<String, Object> forAuthorization(
      OAuth2Authorization authorization, Set<String> scopes) {
    Authentication principal = authorization.getAttribute(Principal.class.getName());
    if (principal == null
        || !(principal.getPrincipal() instanceof AuthenticatedIdentity identity)) {
      throw new OAuth2AuthenticationException("invalid_token");
    }
    var profile =
        profiles
            .findCurrent(UUID.fromString(identity.getName()), identity.version())
            .orElseThrow(() -> new OAuth2AuthenticationException("invalid_token"));
    Map<String, Object> claims = new HashMap<>();
    claims.put("sub", identity.getName());
    if (scopes.contains("profile")) {
      claims.put("name", profile.name());
    }
    if (scopes.contains("email")) {
      claims.put("email", profile.email());
    }
    // Provisioning an address does not verify its ownership: do not assert email_verified.
    return claims;
  }
}

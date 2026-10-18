package com.securegate.iam.authorization.infrastructure;

import com.securegate.iam.authorization.application.port.ConsentRequests;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Binds pending consent/code state to an opaque browser session, never the JSESSIONID. */
public final class BrowserAuthorizations implements OAuth2AuthorizationService {
  private final OAuth2AuthorizationService delegate;

  public BrowserAuthorizations(OAuth2AuthorizationService delegate) {
    this.delegate = delegate;
  }

  @Override
  public void save(OAuth2Authorization authorization) {
    if (AuthorizationGrantType.AUTHORIZATION_CODE.equals(authorization.getAuthorizationGrantType())
        && authorization.getAttribute(ConsentRequests.SESSION_ID) == null) {
      var attributes = RequestContextHolder.getRequestAttributes();
      if (!(attributes instanceof ServletRequestAttributes servlet)) {
        throw new OAuth2AuthenticationException("access_denied");
      }
      HttpServletRequest request = servlet.getRequest();
      var session = request.getSession(false);
      if (session == null) {
        throw new OAuth2AuthenticationException("access_denied");
      }
      Object sid = session.getAttribute(ConsentRequests.SESSION_ID);
      if (!(sid instanceof String)) {
        sid = UUID.randomUUID().toString();
        session.setAttribute(ConsentRequests.SESSION_ID, sid);
      }
      authorization =
          OAuth2Authorization.from(authorization)
              .attribute(ConsentRequests.SESSION_ID, sid)
              .attribute(
                  OidcIdentityClaims.AUTH_TIME, session.getAttribute(OidcIdentityClaims.AUTH_TIME))
              .attribute(ConsentRequests.EXPIRES, Instant.now().plusSeconds(300).toString())
              .build();
    }
    delegate.save(authorization);
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

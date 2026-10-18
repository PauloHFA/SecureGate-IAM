package com.securegate.iam.authorization.infrastructure;

import com.securegate.iam.authorization.application.port.ConsentRequests;
import com.securegate.iam.identity.application.port.AuthenticatedIdentity;
import com.securegate.iam.identity.application.port.IdentityLookup;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public final class CurrentBrowserIdentityFilter extends OncePerRequestFilter {
  private final IdentityLookup identities;
  private final ConsentRequests consents;

  public CurrentBrowserIdentityFilter(IdentityLookup identities, ConsentRequests consents) {
    this.identities = identities;
    this.consents = consents;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null
        && authentication.getPrincipal() instanceof AuthenticatedIdentity identity
        && !identities.isCurrent(UUID.fromString(identity.getName()), identity.version())) {
      var session = request.getSession(false);
      if (session != null) {
        session.invalidate();
      }
      SecurityContextHolder.clearContext();
    }
    authentication = SecurityContextHolder.getContext().getAuthentication();
    if ("POST".equals(request.getMethod())
        && "/oauth2/authorize".equals(request.getServletPath())
        && authentication != null
        && authentication.getPrincipal() instanceof AuthenticatedIdentity identity) {
      var session = request.getSession(false);
      String sid =
          session == null ? null : (String) session.getAttribute(ConsentRequests.SESSION_ID);
      if (consents
          .find(
              request.getParameter("state"),
              request.getParameter("client_id"),
              identity.getName(),
              sid)
          .isEmpty()) {
        response.setStatus(403);
        response.setHeader("Cache-Control", "no-store");
        return;
      }
    }
    chain.doFilter(request, response);
  }
}

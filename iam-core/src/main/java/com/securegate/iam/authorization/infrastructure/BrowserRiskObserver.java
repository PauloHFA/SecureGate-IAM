package com.securegate.iam.authorization.infrastructure;

import com.securegate.iam.authorization.application.port.ConsentRequests;
import com.securegate.iam.identity.application.port.AuthenticatedIdentity;
import com.securegate.iam.policy.application.port.RiskContextSink;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** A remembered browser identifier, not hardware attestation or an authentication factor. */
public final class BrowserRiskObserver extends OncePerRequestFilter {
  public static final String COOKIE = "sg_device";
  private static final String DEVICE = "securegate.risk.device";
  private static final String USER = "securegate.risk.user";
  private final RiskContextSink sink;

  public BrowserRiskObserver(RiskContextSink sink) {
    this.sink = sink;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if ("GET".equals(request.getMethod())) {
      capture(request, response, SecurityContextHolder.getContext().getAuthentication());
    }
    chain.doFilter(request, response);
  }

  public void capture(
      HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
    var session = request.getSession(false);
    if (session == null
        || authentication == null
        || !(authentication.getPrincipal() instanceof AuthenticatedIdentity identity)
        || !(session.getAttribute(ConsentRequests.SESSION_ID) instanceof String sid)) {
      return;
    }
    String cookie = null;
    if (request.getCookies() != null) {
      for (var value : request.getCookies()) {
        if (COOKIE.equals(value.getName())) {
          cookie = value.getValue();
          break;
        }
      }
    }
    String hint = session.getAttribute(DEVICE) instanceof String value ? value : cookie;
    var device =
        sink.observe(
            UUID.fromString(identity.getName()),
            identity.version(),
            UUID.fromString(sid),
            hint,
            request.getRemoteAddr());
    session.setAttribute(DEVICE, device.toString());
    session.setAttribute(USER, identity.getName());
    if (!device.toString().equals(cookie)) {
      response.addHeader(
          "Set-Cookie",
          ResponseCookie.from(COOKIE, device.toString())
              .httpOnly(true)
              .secure(request.isSecure())
              .sameSite("Lax")
              .path("/")
              .maxAge(Duration.ofDays(30))
              .build()
              .toString());
    }
  }

  public void close(HttpServletRequest request, Authentication authentication) {
    var session = request.getSession(false);
    if (session != null
        && authentication != null
        && authentication.getPrincipal() instanceof AuthenticatedIdentity identity
        && session.getAttribute(ConsentRequests.SESSION_ID) instanceof String sid) {
      sink.close(UUID.fromString(identity.getName()), UUID.fromString(sid));
    }
  }

  public void closePrevious(HttpServletRequest request) {
    var session = request.getSession(false);
    if (session != null) {
      if (session.getAttribute(USER) instanceof String user
          && session.getAttribute(ConsentRequests.SESSION_ID) instanceof String sid) {
        sink.close(UUID.fromString(user), UUID.fromString(sid));
      }
      session.removeAttribute(USER);
      session.removeAttribute(DEVICE);
    }
  }
}

package com.securegate.iam.authorization.infrastructure;

import com.securegate.iam.authorization.application.TokenAccessService;
import com.securegate.iam.authorization.application.TokenAccessService.Navigation;
import com.securegate.iam.authorization.application.port.AuthorizationContinuation;
import com.securegate.iam.authorization.application.port.ConsentRequests;
import com.securegate.iam.identity.application.port.AuthenticatedIdentity;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationContext;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UriComponentsBuilder;

/** Surrounds SAS without replacing its protocol error handler or granting consent itself. */
public final class BrowserAuthorizationNavigation extends OncePerRequestFilter
    implements Consumer<OAuth2AuthorizationCodeRequestAuthenticationContext> {
  private final TokenAccessService access;
  private final AuthorizationContinuation continuations;
  private final OAuth2AuthorizationService authorizations;
  private final ConsentRequests consents;

  public BrowserAuthorizationNavigation(
      TokenAccessService access,
      AuthorizationContinuation continuations,
      OAuth2AuthorizationService authorizations,
      ConsentRequests consents) {
    this.access = access;
    this.continuations = continuations;
    this.authorizations = authorizations;
    this.consents = consents;
  }

  @Override
  public void accept(OAuth2AuthorizationCodeRequestAuthenticationContext context) {
    OAuth2AuthorizationCodeRequestAuthenticationToken input = context.getAuthentication();
    var principal = (Authentication) input.getPrincipal();
    if (principal == null
        || !principal.isAuthenticated()
        || !(principal.getPrincipal() instanceof AuthenticatedIdentity)) {
      return;
    }
    var attributes = RequestContextHolder.getRequestAttributes();
    if (!(attributes instanceof ServletRequestAttributes servlet)) {
      throw new NavigationUnavailable();
    }
    var params =
        parameters(
            input.getClientId(),
            input.getRedirectUri(),
            input.getState(),
            input.getScopes(),
            input.getAdditionalParameters());
    check(principal, servlet.getRequest(), context.getRegisteredClient().getClientName(), params);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    try {
      if ("POST".equals(request.getMethod())
          && "/oauth2/authorize".equals(request.getServletPath())) {
        checkConsent(request);
      }
      chain.doFilter(request, response);
    } catch (Stop navigation) {
      response.setHeader("Cache-Control", "no-store");
      response.setHeader("Referrer-Policy", "no-referrer");
      boolean silent =
          navigation.parameters.getOrDefault("scope", "").split(" ").length > 0
              && Set.of(navigation.parameters.getOrDefault("scope", "").split(" "))
                  .contains("openid")
              && Arrays.asList(navigation.parameters.getOrDefault("prompt", "").split(" "))
                  .contains("none");
      if (navigation.outcome == Navigation.MFA_REQUIRED && !silent) {
        try {
          continuations.begin(
              navigation.user,
              navigation.version,
              navigation.sid,
              navigation.clientName,
              navigation.parameters);
          if (navigation.consentState != null) {
            var pending =
                authorizations.findByToken(navigation.consentState, new OAuth2TokenType("state"));
            if (pending != null) {
              authorizations.remove(pending);
            }
          }
          // Keep the handoff relative instead of deriving an absolute URL from the Host header.
          response.setStatus(302);
          response.setHeader("Location", "/mfa");
        } catch (RuntimeException failure) {
          unavailable(response);
        }
      } else {
        String error =
            navigation.outcome == Navigation.MFA_REQUIRED
                ? "interaction_required"
                : "access_denied";
        response.sendRedirect(
            UriComponentsBuilder.fromUriString(navigation.parameters.get("redirect_uri"))
                .queryParam("error", error)
                .queryParam("state", navigation.parameters.get("state"))
                .build(true)
                .toUriString());
      }
    } catch (NavigationUnavailable failure) {
      unavailable(response);
    }
  }

  private void checkConsent(HttpServletRequest request) {
    // A denial is always processed by SAS, without forcing MFA or retaining a continuation.
    String[] selected = request.getParameterValues("scope");
    if (selected == null || selected.length == 0) {
      return;
    }
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null
        || !(authentication.getPrincipal() instanceof AuthenticatedIdentity identity)) {
      return;
    }
    var session = request.getSession(false);
    String sid = session == null ? null : (String) session.getAttribute(ConsentRequests.SESSION_ID);
    String state = request.getParameter("state");
    var view =
        consents
            .find(state, request.getParameter("client_id"), identity.getName(), sid)
            .orElse(null);
    if (view == null || !view.scopes().containsAll(Arrays.asList(selected))) {
      return;
    }
    var authorization = authorizations.findByToken(state, new OAuth2TokenType("state"));
    if (authorization == null) {
      return;
    }
    OAuth2AuthorizationRequest original =
        authorization.getAttribute(OAuth2AuthorizationRequest.class.getName());
    if (original == null) {
      throw new NavigationUnavailable();
    }
    try {
      check(
          authentication,
          request,
          view.name(),
          parameters(
              original.getClientId(),
              original.getRedirectUri(),
              original.getState(),
              original.getScopes(),
              original.getAdditionalParameters()));
    } catch (Stop navigation) {
      navigation.consentState = state;
      throw navigation;
    }
  }

  private void check(
      Authentication principal,
      HttpServletRequest request,
      String clientName,
      Map<String, String> parameters) {
    var identity = (AuthenticatedIdentity) principal.getPrincipal();
    var session = request.getSession(false);
    if (session == null
        || !(session.getAttribute(ConsentRequests.SESSION_ID) instanceof String sid)) {
      throw new NavigationUnavailable();
    }
    Navigation result;
    UUID user;
    UUID sessionId;
    try {
      user = UUID.fromString(identity.getName());
      sessionId = UUID.fromString(sid);
      result =
          access.navigation(
              user,
              identity.version(),
              sessionId,
              parameters.get("client_id"),
              Set.copyOf(Arrays.asList(parameters.get("scope").split(" "))));
    } catch (RuntimeException failure) {
      throw new NavigationUnavailable();
    }
    if (result != Navigation.READY) {
      throw new Stop(result, parameters, clientName, user, identity.version(), sessionId);
    }
  }

  private static Map<String, String> parameters(
      String client,
      String redirect,
      String state,
      Set<String> scopes,
      Map<String, Object> additional) {
    var params = new HashMap<String, String>();
    params.put("response_type", "code");
    params.put("client_id", client);
    params.put("redirect_uri", redirect);
    params.put("state", state);
    params.put("scope", scopes.stream().sorted().collect(java.util.stream.Collectors.joining(" ")));
    for (String name : Set.of("code_challenge", "code_challenge_method", "nonce", "prompt")) {
      if (additional.get(name) instanceof String value) {
        params.put(name, value);
      }
    }
    return Map.copyOf(params);
  }

  private static void unavailable(HttpServletResponse response) throws IOException {
    response.setStatus(503);
    response.setHeader("Cache-Control", "no-store");
    response.setHeader("Retry-After", "5");
    response.setContentType("application/json");
    response.getWriter().write("{\"error\":\"temporarily_unavailable\"}");
  }

  private static final class NavigationUnavailable extends RuntimeException {}

  private static final class Stop extends RuntimeException {
    private final Navigation outcome;
    private final Map<String, String> parameters;
    private final String clientName;
    private final UUID user;
    private final long version;
    private final UUID sid;
    private String consentState;

    private Stop(
        Navigation outcome,
        Map<String, String> parameters,
        String clientName,
        UUID user,
        long version,
        UUID sid) {
      super("Authorization navigation interrupted.");
      this.outcome = outcome;
      this.parameters = parameters;
      this.clientName = clientName;
      this.user = user;
      this.version = version;
      this.sid = sid;
    }
  }
}

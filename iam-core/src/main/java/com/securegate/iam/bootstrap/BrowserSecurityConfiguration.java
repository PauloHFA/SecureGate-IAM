package com.securegate.iam.bootstrap;

import com.securegate.iam.authorization.application.TokenAccessService;
import com.securegate.iam.authorization.application.port.AuthorizationContinuation;
import com.securegate.iam.authorization.application.port.ConsentRequests;
import com.securegate.iam.authorization.infrastructure.BrowserAuthorizationNavigation;
import com.securegate.iam.authorization.infrastructure.BrowserRiskObserver;
import com.securegate.iam.authorization.infrastructure.CurrentBrowserIdentityFilter;
import com.securegate.iam.authorization.infrastructure.OidcIdentityClaims;
import com.securegate.iam.authorization.infrastructure.StrictAuthorizationRequest;
import com.securegate.iam.identity.application.port.IdentityLookup;
import com.securegate.iam.identity.application.port.LoginRiskHistory;
import com.securegate.iam.identity.application.port.MfaProofs;
import com.securegate.iam.identity.infrastructure.JdbcLoginUsers;
import com.securegate.iam.policy.application.port.RiskContextSink;
import com.securegate.iam.shared.application.port.UnitOfWork;
import com.securegate.iam.shared.presentation.ProblemResponses;
import java.net.URI;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "iam.authorization.enabled", havingValue = "true")
public class BrowserSecurityConfiguration {
  @Bean
  org.springframework.security.core.session.SessionRegistry sessionRegistry() {
    return new org.springframework.security.core.session.SessionRegistryImpl();
  }

  @Bean
  org.springframework.security.web.session.HttpSessionEventPublisher httpSessionEventPublisher() {
    return new org.springframework.security.web.session.HttpSessionEventPublisher();
  }

  @Bean
  @Order(2)
  SecurityFilterChain browserSecurity(
      HttpSecurity http,
      JdbcTemplate jdbc,
      IdentityLookup identities,
      ProblemResponses problems,
      ConsentRequests consents,
      RiskContextSink riskContexts,
      LoginRiskHistory failures,
      MfaProofs mfaProofs,
      TokenAccessService access,
      AuthorizationContinuation continuations,
      UnitOfWork transactions,
      org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService
          authorizations)
      throws Exception {
    var provider = new DaoAuthenticationProvider(new JdbcLoginUsers(jdbc));
    var observer = new BrowserRiskObserver(riskContexts);
    var navigation =
        new BrowserAuthorizationNavigation(access, continuations, authorizations, consents);
    provider.setPasswordEncoder(new Argon2PasswordEncoder(16, 32, 1, 19456, 2));
    // Verify the password (or the provider's dummy hash) before reporting any account state.
    provider.setPreAuthenticationChecks(user -> {});
    provider.setPostAuthenticationChecks(
        user -> {
          if (!user.isEnabled()) {
            throw new BadCredentialsException("Bad credentials");
          }
        });
    var cache = new HttpSessionRequestCache();
    cache.setRequestMatcher(
        request ->
            "GET".equals(request.getMethod())
                && "/oauth2/authorize".equals(request.getServletPath()));
    // SAS excludes its endpoints from the default CSRF filter. Independently protect consent POSTs.
    var consentCsrf = new CsrfFilter(new HttpSessionCsrfTokenRepository());
    consentCsrf.setBeanName("consentCsrf");
    consentCsrf.setRequireCsrfProtectionMatcher(
        request ->
            "POST".equals(request.getMethod())
                && "/oauth2/authorize".equals(request.getServletPath()));
    consentCsrf.setAccessDeniedHandler(
        (request, response, exception) -> problems.forbidden(request, response));
    return http.securityMatcher(
            "/login",
            "/logout",
            "/mfa",
            "/mfa/**",
            "/oauth2/authorize",
            "/oauth2/consent",
            "/oauth2/continue")
        .with(
            OAuth2AuthorizationServerConfigurer.authorizationServer(),
            server ->
                server
                    .oidc(org.springframework.security.config.Customizer.withDefaults())
                    .authorizationEndpoint(
                        endpoint ->
                            endpoint
                                .consentPage("/oauth2/consent")
                                .authenticationProviders(
                                    providers ->
                                        providers.forEach(
                                            auth -> {
                                              if (auth
                                                  instanceof
                                                  OAuth2AuthorizationCodeRequestAuthenticationProvider
                                                      authorization) {
                                                authorization.setAuthenticationValidator(
                                                    new StrictAuthorizationRequest()
                                                        .andThen(navigation));
                                              }
                                            }))))
        .authenticationProvider(provider)
        .authorizeHttpRequests(
            requests -> requests.requestMatchers("/login").permitAll().anyRequest().authenticated())
        .requestCache(requests -> requests.requestCache(cache))
        .sessionManagement(
            session -> session.sessionFixation(fixation -> fixation.changeSessionId()))
        .addFilterBefore(new CurrentBrowserIdentityFilter(identities, consents), CsrfFilter.class)
        .addFilterAfter(consentCsrf, CsrfFilter.class)
        .addFilterAfter(observer, CurrentBrowserIdentityFilter.class)
        // After both CSRF checks and before the SAS authorization endpoint filter.
        .addFilterBefore(
            navigation, org.springframework.security.web.authentication.logout.LogoutFilter.class)
        .formLogin(
            form ->
                form.loginPage("/login")
                    .failureHandler(
                        (request, response, failure) -> {
                          failures.recordFailure(
                              request.getParameter("username"), request.getRemoteAddr());
                          new org.springframework.security.web.authentication
                                  .SimpleUrlAuthenticationFailureHandler("/login?error")
                              .onAuthenticationFailure(request, response, failure);
                        })
                    .successHandler(
                        (request, response, authentication) -> {
                          try {
                            transactions.execute(
                                () -> {
                                  continuations.clear();
                                  revokeMfa(request, mfaProofs);
                                  observer.closePrevious(request);
                                  if (authentication
                                      instanceof
                                      org.springframework.security.authentication
                                              .AbstractAuthenticationToken
                                          token) {
                                    token.setDetails(null);
                                  }
                                  request
                                      .getSession()
                                      .setAttribute(
                                          ConsentRequests.SESSION_ID, UUID.randomUUID().toString());
                                  request
                                      .getSession()
                                      .setAttribute(
                                          OidcIdentityClaims.AUTH_TIME,
                                          java.time.Instant.now().toString());
                                  observer.capture(request, response, authentication);
                                  return null;
                                });
                          } catch (RuntimeException unavailable) {
                            org.springframework.security.core.context.SecurityContextHolder
                                .clearContext();
                            var failedSession = request.getSession(false);
                            if (failedSession != null) {
                              failedSession.invalidate();
                            }
                            response.setStatus(503);
                            response.setContentType("application/json");
                            response.setHeader("Cache-Control", "no-store");
                            response.getWriter().write("{\"error\":\"temporarily_unavailable\"}");
                            return;
                          }
                          var saved = cache.getRequest(request, response);
                          cache.removeRequest(request, response);
                          String destination = "/login?authenticated";
                          if (saved != null) {
                            URI uri = URI.create(saved.getRedirectUrl());
                            if ("/oauth2/authorize".equals(uri.getPath())) {
                              destination =
                                  uri.getRawPath()
                                      + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
                            }
                          }
                          response.sendRedirect(destination);
                        }))
        .logout(
            logout ->
                logout
                    .addLogoutHandler(
                        (request, response, authentication) -> {
                          transactions.execute(
                              () -> {
                                continuations.clear();
                                revokeMfa(request, mfaProofs);
                                observer.close(request, authentication);
                                return null;
                              });
                        })
                    .logoutSuccessUrl("/login?logout"))
        .headers(
            headers ->
                headers.referrerPolicy(
                    policy ->
                        policy.policy(
                            org.springframework.security.web.header.writers
                                .ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
        .exceptionHandling(
            errors ->
                errors
                    .authenticationEntryPoint(new LoginUrlAuthenticationEntryPoint("/login"))
                    .accessDeniedHandler(
                        (request, response, exception) -> problems.forbidden(request, response)))
        .build();
  }

  private static void revokeMfa(jakarta.servlet.http.HttpServletRequest request, MfaProofs proofs) {
    var session = request.getSession(false);
    if (session != null && session.getAttribute(ConsentRequests.SESSION_ID) instanceof String sid) {
      proofs.revokeSession(UUID.fromString(sid));
    }
  }
}

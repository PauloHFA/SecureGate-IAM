package com.securegate.iam.bootstrap;

import com.securegate.iam.authorization.application.port.ConsentRequests;
import com.securegate.iam.authorization.infrastructure.CurrentBrowserIdentityFilter;
import com.securegate.iam.authorization.infrastructure.StrictAuthorizationRequest;
import com.securegate.iam.identity.application.port.IdentityLookup;
import com.securegate.iam.identity.infrastructure.JdbcLoginUsers;
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
  @Order(2)
  SecurityFilterChain browserSecurity(
      HttpSecurity http,
      JdbcTemplate jdbc,
      IdentityLookup identities,
      ProblemResponses problems,
      ConsentRequests consents)
      throws Exception {
    var provider = new DaoAuthenticationProvider(new JdbcLoginUsers(jdbc));
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
    return http.securityMatcher("/login", "/logout", "/oauth2/authorize", "/oauth2/consent")
        .with(
            OAuth2AuthorizationServerConfigurer.authorizationServer(),
            server ->
                server.authorizationEndpoint(
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
                                                new StrictAuthorizationRequest());
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
        .formLogin(
            form ->
                form.loginPage("/login")
                    .failureUrl("/login?error")
                    .successHandler(
                        (request, response, authentication) -> {
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
        .logout(logout -> logout.logoutSuccessUrl("/login?logout"))
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
}

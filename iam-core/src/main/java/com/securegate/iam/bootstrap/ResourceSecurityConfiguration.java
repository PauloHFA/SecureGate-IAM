package com.securegate.iam.bootstrap;

import com.securegate.events.SecurityEventType;
import com.securegate.iam.audit.application.port.AuditRecord;
import com.securegate.iam.audit.application.port.AuditTrail;
import com.securegate.iam.authorization.application.PamAudit;
import com.securegate.iam.authorization.application.ResourceAccessService;
import com.securegate.iam.authorization.application.port.ResourceEnforcement;
import com.securegate.iam.authorization.application.port.ResourceTokens;
import com.securegate.iam.authorization.infrastructure.LocalResourceTokens;
import com.securegate.iam.authorization.infrastructure.RefreshTokenLedger;
import com.securegate.iam.identity.application.port.IdentityLookup;
import com.securegate.iam.identity.application.port.MfaProofs;
import com.securegate.iam.policy.application.port.PolicyDecisions;
import com.securegate.iam.shared.application.port.OperationContext;
import com.securegate.iam.shared.presentation.CorrelationIdFilter;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "iam.authorization.enabled", havingValue = "true")
public class ResourceSecurityConfiguration {
  @Bean
  ResourceTokens resourceTokens(
      JwtDecoder decoder,
      OAuth2AuthorizationService authorizations,
      RefreshTokenLedger ledger,
      ResourceEnforcement enforcement) {
    return new LocalResourceTokens(decoder, authorizations, ledger, enforcement);
  }

  @Bean
  ResourceAccessService resourceAccessService(
      ResourceTokens tokens,
      IdentityLookup identities,
      PolicyDecisions policies,
      MfaProofs proofs,
      Clock clock,
      ResourceEnforcement enforcement,
      @Value("${iam.resources.audience:securegate-api}") String audience) {
    if (!audience.matches("[a-zA-Z0-9][a-zA-Z0-9:._/-]{2,127}")) {
      throw new IllegalArgumentException("Invalid resource audience.");
    }
    return new ResourceAccessService(
        tokens, identities, policies, proofs, clock, audience, enforcement);
  }

  @Bean
  @Order(3)
  SecurityFilterChain resourceSecurity(
      HttpSecurity http,
      ResourceAccessService access,
      AuditTrail audit,
      @Value("${iam.pam.enabled:false}") boolean pamEnabled,
      @Value("${iam.pam.credentials.enabled:false}") boolean credentialsEnabled)
      throws Exception {
    return http.securityMatcher("/api/resources/**", "/api/pam/**")
        .csrf(AbstractHttpConfigurer::disable)
        .requestCache(AbstractHttpConfigurer::disable)
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            r ->
                r.requestMatchers(HttpMethod.GET, "/api/resources/account")
                    .authenticated()
                    .requestMatchers(HttpMethod.POST, "/api/pam/requests")
                    .authenticated()
                    .requestMatchers(HttpMethod.GET, "/api/pam/requests/*")
                    .authenticated()
                    .requestMatchers(HttpMethod.DELETE, "/api/pam/requests/*")
                    .authenticated()
                    .requestMatchers(HttpMethod.POST, "/api/pam/requests/*/credentials")
                    .authenticated()
                    .requestMatchers(HttpMethod.GET, "/api/pam/requests/*/credentials")
                    .authenticated()
                    .anyRequest()
                    .denyAll())
        .oauth2ResourceServer(
            oauth ->
                oauth
                    .opaqueToken(
                        opaque ->
                            opaque.introspector(
                                token -> {
                                  try {
                                    var attributes = RequestContextHolder.getRequestAttributes();
                                    if (!(attributes instanceof ServletRequestAttributes current)) {
                                      throw new ResourceAccessService.Rejected(
                                          "insufficient_scope");
                                    }
                                    String method = current.getRequest().getMethod();
                                    String path = current.getRequest().getServletPath();
                                    boolean account =
                                        "GET".equals(method)
                                            && "/api/resources/account".equals(path);
                                    boolean pam =
                                        pamEnabled
                                            && (("POST".equals(method)
                                                    && "/api/pam/requests".equals(path))
                                                || (("GET".equals(method)
                                                        || "DELETE".equals(method))
                                                    && path.matches(
                                                        "/api/pam/requests/[0-9a-fA-F-]{36}")));
                                    boolean credentials =
                                        pamEnabled
                                            && credentialsEnabled
                                            && ("GET".equals(method) || "POST".equals(method))
                                            && path.matches(
                                                "/api/pam/requests/[0-9a-fA-F-]{36}/credentials");
                                    if (!account && !pam && !credentials) {
                                      throw new ResourceAccessService.Rejected(
                                          "insufficient_scope");
                                    }
                                    var permit =
                                        access.authorize(
                                            token,
                                            credentials
                                                ? ResourceAccessService.Operation.PRIVILEGED_ISSUE
                                                : pam
                                                    ? ResourceAccessService.Operation
                                                        .PRIVILEGED_REQUEST
                                                    : ResourceAccessService.Operation.ACCOUNT_READ,
                                            current.getRequest().getRemoteAddr());
                                    if (pam || credentials) {
                                      return new DefaultOAuth2AuthenticatedPrincipal(
                                          permit.userId().toString(),
                                          Map.of("pam_permit", permit),
                                          List.of());
                                    }
                                    Object correlation =
                                        attributes instanceof ServletRequestAttributes servlet
                                            ? servlet
                                                .getRequest()
                                                .getAttribute(CorrelationIdFilter.ATTRIBUTE)
                                            : null;
                                    audit.record(
                                        new AuditRecord(
                                            new SecurityEventType("resource.access.allowed"),
                                            "account:self",
                                            new OperationContext(
                                                permit.userId().toString(),
                                                correlation instanceof String id
                                                    ? id
                                                    : UUID.randomUUID().toString()),
                                            Map.of(
                                                "action",
                                                "read",
                                                "scopes",
                                                String.join(" ", permit.scopes()),
                                                "policies",
                                                permit.decision().policies().stream()
                                                    .map(p -> p.id() + ":" + p.version())
                                                    .collect(Collectors.joining(",")),
                                                "mfa",
                                                Boolean.toString(permit.decision().mfaRequired()),
                                                "risk",
                                                Integer.toString(permit.decision().riskScore()),
                                                "expiresAt",
                                                permit.expiresAt().toString())));
                                    return new DefaultOAuth2AuthenticatedPrincipal(
                                        permit.userId().toString(),
                                        Map.of(
                                            "sub",
                                            permit.userId().toString(),
                                            "scope",
                                            permit.scopes().stream().sorted().toList(),
                                            "expires_at",
                                            permit.expiresAt().toString()),
                                        List.of());
                                  } catch (ResourceAccessService.Rejected rejected) {
                                    var attributes = RequestContextHolder.getRequestAttributes();
                                    if (pamEnabled
                                        && rejected.userId() != null
                                        && attributes instanceof ServletRequestAttributes current
                                        && current
                                            .getRequest()
                                            .getServletPath()
                                            .startsWith("/api/pam/")) {
                                      try {
                                        new PamAudit(audit)
                                            .denied(
                                                rejected.userId(),
                                                (String)
                                                    current
                                                        .getRequest()
                                                        .getAttribute(
                                                            CorrelationIdFilter.ATTRIBUTE),
                                                current
                                                        .getRequest()
                                                        .getServletPath()
                                                        .endsWith("/credentials")
                                                    ? ResourceAccessService.Operation
                                                        .PRIVILEGED_ISSUE
                                                    : ResourceAccessService.Operation
                                                        .PRIVILEGED_REQUEST,
                                                rejected.code(),
                                                PamAudit.Stage.AUTHORIZATION);
                                      } catch (RuntimeException failure) {
                                        throw new OAuth2AuthenticationException(
                                            "temporarily_unavailable");
                                      }
                                    }
                                    throw new OAuth2AuthenticationException(rejected.code());
                                  } catch (RuntimeException unavailable) {
                                    throw new OAuth2AuthenticationException(
                                        "temporarily_unavailable");
                                  }
                                }))
                    .authenticationEntryPoint(
                        (request, response, failure) -> {
                          String code =
                              failure instanceof OAuth2AuthenticationException oauthFailure
                                  ? oauthFailure.getError().getErrorCode()
                                  : "invalid_token";
                          int status =
                              switch (code) {
                                case "temporarily_unavailable" -> 503;
                                case "rate_limited" -> 429;
                                case "mfa_required", "insufficient_scope" -> 403;
                                default -> 401;
                              };
                          response.setStatus(status);
                          response.setContentType("application/json");
                          response.setHeader("Cache-Control", "no-store");
                          if (status == 503 || status == 429) {
                            response.setHeader("Retry-After", "5");
                          } else {
                            response.setHeader(
                                "WWW-Authenticate",
                                "Bearer error=\""
                                    + (status == 401 ? "invalid_token" : "insufficient_scope")
                                    + "\"");
                          }
                          response
                              .getWriter()
                              .write(
                                  "{\"error\":\""
                                      + (status == 401 ? "invalid_token" : code)
                                      + "\"}");
                        }))
        .build();
  }
}

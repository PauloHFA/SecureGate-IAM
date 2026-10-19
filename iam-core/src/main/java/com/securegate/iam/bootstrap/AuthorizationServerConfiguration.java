package com.securegate.iam.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securegate.iam.access.application.port.EffectiveRoles;
import com.securegate.iam.audit.application.port.AuditTrail;
import com.securegate.iam.authorization.application.AuthorizationContinuationService;
import com.securegate.iam.authorization.application.OAuthClientService;
import com.securegate.iam.authorization.application.TokenAccessService;
import com.securegate.iam.authorization.application.port.AuthorizationContinuation;
import com.securegate.iam.authorization.application.port.ClientRegistry;
import com.securegate.iam.authorization.application.port.ConsentRequests;
import com.securegate.iam.authorization.application.port.SigningKeys;
import com.securegate.iam.authorization.infrastructure.AuthorizationJdbcDataSource;
import com.securegate.iam.authorization.infrastructure.BrowserAuthorizations;
import com.securegate.iam.authorization.infrastructure.FamilyBoundRefreshGenerator;
import com.securegate.iam.authorization.infrastructure.JdbcClientRegistry;
import com.securegate.iam.authorization.infrastructure.JdbcConsentRequests;
import com.securegate.iam.authorization.infrastructure.OidcIdentityClaims;
import com.securegate.iam.authorization.infrastructure.OwnedTokenProvider;
import com.securegate.iam.authorization.infrastructure.PolicyGrantProvider;
import com.securegate.iam.authorization.infrastructure.PolicyIssuance;
import com.securegate.iam.authorization.infrastructure.RefreshTokenLedger;
import com.securegate.iam.authorization.infrastructure.RefreshTrackingAuthorizations;
import com.securegate.iam.authorization.infrastructure.RotatingRefreshProvider;
import com.securegate.iam.authorization.infrastructure.SerializedCodeRedemption;
import com.securegate.iam.authorization.infrastructure.SessionContinuationStore;
import com.securegate.iam.authorization.infrastructure.TransitPublicJwtDecoder;
import com.securegate.iam.authorization.presentation.OAuthFailureHandler;
import com.securegate.iam.identity.application.port.AuthenticatedIdentity;
import com.securegate.iam.identity.application.port.IdentityLookup;
import com.securegate.iam.identity.application.port.IdentityProfiles;
import com.securegate.iam.identity.application.port.MfaProofs;
import com.securegate.iam.identity.application.port.PasswordHasher;
import com.securegate.iam.identity.infrastructure.JdbcIdentityProfiles;
import com.securegate.iam.policy.application.port.PolicyDecisions;
import com.securegate.iam.shared.application.port.OperationContext;
import com.securegate.iam.shared.application.port.UnitOfWork;
import com.securegate.iam.shared.presentation.CorrelationIdFilter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncodingException;
import org.springframework.security.oauth2.server.authorization.*;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientCredentialsAuthenticationContext;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientCredentialsAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientCredentialsAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientCredentialsAuthenticationValidator;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2RefreshTokenAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2TokenIntrospectionAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2TokenRevocationAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.client.*;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.token.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "iam.authorization.enabled", havingValue = "true")
@EnableConfigurationProperties(AuthorizationProperties.class)
public class AuthorizationServerConfiguration {
  @Bean
  AuthorizationContinuation authorizationContinuation(
      IdentityLookup identities, MfaProofs proofs, java.time.Clock clock) {
    return new AuthorizationContinuationService(
        new SessionContinuationStore(), identities, proofs, clock);
  }

  @Bean
  TokenAccessService tokenAccessService(
      PolicyDecisions policies,
      IdentityLookup identities,
      MfaProofs proofs,
      com.securegate.iam.authorization.application.port.AuthenticationEnforcement enforcement) {
    return new TokenAccessService(policies, identities, proofs, enforcement);
  }

  @Bean
  PolicyIssuance policyIssuance(
      TokenAccessService access,
      com.securegate.iam.authorization.application.port.AuthenticationEnforcement enforcement) {
    return new PolicyIssuance(access, enforcement);
  }

  @Bean
  IdentityProfiles identityProfiles(JdbcTemplate jdbc) {
    return new JdbcIdentityProfiles(jdbc);
  }

  @Bean
  OidcIdentityClaims oidcIdentityClaims(IdentityProfiles profiles) {
    return new OidcIdentityClaims(profiles);
  }

  @Bean
  JwtDecoder jwtDecoder(SigningKeys keys, AuthorizationProperties properties) {
    return new TransitPublicJwtDecoder(keys, properties.issuer().toString());
  }

  @Bean
  @DependsOn("flywayInitializer")
  RegisteredClientRepository registeredClientRepository(DataSource dataSource) {
    return new JdbcRegisteredClientRepository(
        new JdbcTemplate(new AuthorizationJdbcDataSource(dataSource)));
  }

  @Bean
  @DependsOn("flywayInitializer")
  OAuth2AuthorizationService authorizationService(
      DataSource dataSource, RegisteredClientRepository clients, RefreshTokenLedger ledger) {
    return new RefreshTrackingAuthorizations(
        new BrowserAuthorizations(
            new JdbcOAuth2AuthorizationService(
                new JdbcTemplate(new AuthorizationJdbcDataSource(dataSource)), clients)),
        ledger);
  }

  @Bean
  RefreshTokenLedger refreshTokenLedger(JdbcTemplate jdbc, AuditTrail audit) {
    return new RefreshTokenLedger(
        jdbc,
        audit,
        () -> {
          var authentication = SecurityContextHolder.getContext().getAuthentication();
          String actor = authentication == null ? "system" : authentication.getName();
          var attributes = RequestContextHolder.getRequestAttributes();
          Object correlation =
              attributes instanceof ServletRequestAttributes servlet
                  ? servlet.getRequest().getAttribute(CorrelationIdFilter.ATTRIBUTE)
                  : null;
          return new OperationContext(
              actor, correlation instanceof String id ? id : UUID.randomUUID().toString());
        });
  }

  @Bean
  ConsentRequests consentRequests(
      OAuth2AuthorizationService authorizations, RegisteredClientRepository clients) {
    return new JdbcConsentRequests(authorizations, clients);
  }

  @Bean
  OAuth2AuthorizationConsentService consentService(
      DataSource dataSource, RegisteredClientRepository clients) {
    return new JdbcOAuth2AuthorizationConsentService(
        new JdbcTemplate(new AuthorizationJdbcDataSource(dataSource)), clients);
  }

  @Bean
  ClientRegistry clientRegistry(RegisteredClientRepository clients) {
    return new JdbcClientRegistry(clients);
  }

  @Bean
  OAuthClientService oauthClientService(
      ClientRegistry clients, PasswordHasher passwords, AuditTrail audit, UnitOfWork transactions) {
    return new OAuthClientService(clients, passwords, audit, transactions);
  }

  @Bean
  AuthorizationServerSettings authorizationServerSettings(AuthorizationProperties properties) {
    return AuthorizationServerSettings.builder().issuer(properties.issuer().toString()).build();
  }

  @Bean
  OAuth2TokenGenerator<?> tokenGenerator(
      JwtEncoder encoder,
      IdentityLookup identities,
      EffectiveRoles roles,
      RefreshTokenLedger ledger,
      OidcIdentityClaims oidcClaims,
      PolicyIssuance issuance) {
    JwtGenerator generator = new JwtGenerator(encoder);
    generator.setJwtCustomizer(
        context -> {
          boolean idToken = "id_token".equals(context.getTokenType().getValue());
          String audience =
              context
                  .getRegisteredClient()
                  .getClientSettings()
                  .getSetting(JdbcClientRegistry.AUDIENCE);
          if (audience == null || audience.isBlank()) {
            throw new OAuth2AuthenticationException("server_error");
          }
          if (!idToken) {
            if (context.getAuthorization() != null
                && !AuthorizationGrantType.CLIENT_CREDENTIALS.equals(
                    context.getAuthorizationGrantType())) {
              context.getClaims().claim("authorization_id", context.getAuthorization().getId());
            }
            issuance.boundExpiry(context.getClaims());
            context
                .getClaims()
                // JDBC's security allowlist supports ArrayList, not List.of() implementation types.
                .audience(new ArrayList<>(List.of(audience)))
                .claim(
                    "scope",
                    new ArrayList<>(context.getAuthorizedScopes().stream().sorted().toList()))
                .claim("client_id", context.getRegisteredClient().getClientId());
          } else {
            context
                .getClaims()
                .audience(new ArrayList<>(List.of(context.getRegisteredClient().getClientId())))
                .expiresAt(java.time.Instant.now().plusSeconds(300));
            var identityClaims =
                oidcClaims.forAuthorization(
                    context.getAuthorization(), context.getAuthorizedScopes());
            identityClaims.forEach((name, value) -> context.getClaims().claim(name, value));
            String authTime = context.getAuthorization().getAttribute(OidcIdentityClaims.AUTH_TIME);
            if (authTime != null) {
              context.getClaims().claim("auth_time", java.time.Instant.parse(authTime));
            }
          }
          if (AuthorizationGrantType.AUTHORIZATION_CODE.equals(context.getAuthorizationGrantType())
              || AuthorizationGrantType.REFRESH_TOKEN.equals(context.getAuthorizationGrantType())) {
            if (!(context.getPrincipal().getPrincipal() instanceof AuthenticatedIdentity identity)
                || !identities.isCurrent(UUID.fromString(identity.getName()), identity.version())
                || context.getAuthorization() == null
                || context.getAuthorization().getAttribute(ConsentRequests.SESSION_ID) == null) {
              throw new OAuth2AuthenticationException("invalid_grant");
            }
            context
                .getClaims()
                .claim("sid", context.getAuthorization().getAttribute(ConsentRequests.SESSION_ID));
            if (!idToken) {
              context
                  .getClaims()
                  .claim(
                      "roles", new ArrayList<>(roles.forUser(UUID.fromString(identity.getName()))));
            }
          }
        });
    var tokens =
        new DelegatingOAuth2TokenGenerator(generator, new FamilyBoundRefreshGenerator(ledger));
    return context -> {
      try {
        return tokens.generate(issuance.constrain(context));
      } catch (JwtEncodingException exception) {
        throw new OAuth2AuthenticationException(new OAuth2Error("temporarily_unavailable"));
      }
    };
  }

  @Bean
  @Order(0)
  SecurityFilterChain authorizationSecurity(
      HttpSecurity http,
      ObjectMapper json,
      JdbcTemplate jdbc,
      PlatformTransactionManager manager,
      RefreshTokenLedger ledger,
      OAuth2AuthorizationService authorizations,
      IdentityLookup identities,
      OidcIdentityClaims oidcClaims,
      PolicyIssuance issuance,
      AuditTrail audit)
      throws Exception {
    var failure = new OAuthFailureHandler(json);
    var server = OAuth2AuthorizationServerConfigurer.authorizationServer();
    return http.securityMatcher(
            "/oauth2/token",
            "/oauth2/introspect",
            "/oauth2/revoke",
            "/userinfo",
            "/.well-known/openid-configuration",
            "/.well-known/oauth-authorization-server")
        .with(
            server,
            config ->
                config
                    .oidc(
                        oidc ->
                            oidc.providerConfigurationEndpoint(
                                    endpoint ->
                                        endpoint.providerConfigurationCustomizer(
                                            builder ->
                                                builder.claims(
                                                    AuthorizationServerConfiguration
                                                        ::supportedMetadata)))
                                .userInfoEndpoint(
                                    endpoint ->
                                        endpoint.userInfoMapper(
                                            context ->
                                                new org.springframework.security.oauth2.core.oidc
                                                    .OidcUserInfo(
                                                    oidcClaims.forAuthorization(
                                                        context.getAuthorization(),
                                                        context.getAccessToken().getScopes())))))
                    .authorizationServerMetadataEndpoint(
                        endpoint ->
                            endpoint.authorizationServerMetadataCustomizer(
                                builder ->
                                    builder.claims(
                                        AuthorizationServerConfiguration::supportedMetadata)))
                    .clientAuthentication(client -> client.errorResponseHandler(failure))
                    .tokenEndpoint(
                        token ->
                            token
                                .errorResponseHandler(failure)
                                .authenticationProviders(
                                    providers ->
                                        configureGrantProviders(
                                            providers,
                                            jdbc,
                                            manager,
                                            ledger,
                                            authorizations,
                                            issuance,
                                            audit)))
                    .tokenIntrospectionEndpoint(
                        endpoint ->
                            endpoint
                                .errorResponseHandler(failure)
                                .authenticationProviders(
                                    providers ->
                                        configureOwnedProviders(
                                            providers,
                                            authorizations,
                                            identities,
                                            ledger,
                                            jdbc,
                                            manager)))
                    .tokenRevocationEndpoint(
                        endpoint ->
                            endpoint
                                .errorResponseHandler(failure)
                                .authenticationProviders(
                                    providers ->
                                        configureOwnedProviders(
                                            providers,
                                            authorizations,
                                            identities,
                                            ledger,
                                            jdbc,
                                            manager))))
        .authorizeHttpRequests(
            requests ->
                requests
                    .requestMatchers(
                        org.springframework.http.HttpMethod.GET,
                        "/.well-known/openid-configuration",
                        "/.well-known/oauth-authorization-server")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .csrf(
            csrf ->
                csrf.ignoringRequestMatchers(
                    "/oauth2/token", "/oauth2/introspect", "/oauth2/revoke", "/userinfo"))
        .sessionManagement(
            sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .requestCache(AbstractHttpConfigurer::disable)
        .exceptionHandling(
            errors ->
                errors.authenticationEntryPoint(
                    (request, response, exception) -> {
                      if ("/userinfo".equals(request.getServletPath())) {
                        new org.springframework.security.oauth2.server.resource.web
                                .BearerTokenAuthenticationEntryPoint()
                            .commence(request, response, exception);
                      } else {
                        failure.onAuthenticationFailure(
                            request, response, new OAuth2AuthenticationException("invalid_client"));
                      }
                    }))
        .build();
  }

  private static void supportedMetadata(java.util.Map<String, Object> claims) {
    claims.put(
        "grant_types_supported",
        List.of("authorization_code", "client_credentials", "refresh_token"));
    claims.put("token_endpoint_auth_methods_supported", List.of("client_secret_basic"));
    claims.put("revocation_endpoint_auth_methods_supported", List.of("client_secret_basic"));
    claims.put("introspection_endpoint_auth_methods_supported", List.of("client_secret_basic"));
    claims.put("scopes_supported", List.of("openid", "profile", "email"));
    claims.put(
        "claims_supported",
        List.of(
            "iss",
            "sub",
            "aud",
            "exp",
            "iat",
            "auth_time",
            "nonce",
            "azp",
            "sid",
            "name",
            "email"));
    claims.put("claims_parameter_supported", false);
    claims.put("request_parameter_supported", false);
    claims.put("request_uri_parameter_supported", false);
    claims.remove("device_authorization_endpoint");
    claims.remove("end_session_endpoint");
    claims.remove("tls_client_certificate_bound_access_tokens");
    claims.remove("dpop_signing_alg_values_supported");
  }

  private static void configureGrantProviders(
      List<AuthenticationProvider> providers,
      JdbcTemplate jdbc,
      PlatformTransactionManager manager,
      RefreshTokenLedger ledger,
      OAuth2AuthorizationService authorizations,
      PolicyIssuance issuance,
      AuditTrail audit) {
    for (int index = 0; index < providers.size(); index++) {
      var provider = providers.get(index);
      if (provider instanceof OAuth2ClientCredentialsAuthenticationProvider credentials) {
        credentials.setAuthenticationValidator(
            new OAuth2ClientCredentialsAuthenticationValidator()
                .andThen(AuthorizationServerConfiguration::requireScopes));
      } else if (provider instanceof OAuth2AuthorizationCodeAuthenticationProvider) {
        providers.set(
            index,
            new SerializedCodeRedemption(
                new PolicyGrantProvider(
                    provider,
                    issuance,
                    authorizations,
                    audit,
                    AuthorizationServerConfiguration::policyCorrelation),
                jdbc,
                new TransactionTemplate(manager)));
      } else if (provider instanceof OAuth2RefreshTokenAuthenticationProvider) {
        providers.set(
            index,
            new RotatingRefreshProvider(
                new PolicyGrantProvider(
                    provider,
                    issuance,
                    authorizations,
                    audit,
                    AuthorizationServerConfiguration::policyCorrelation),
                ledger,
                authorizations,
                new TransactionTemplate(manager)));
      }
    }
  }

  private static void configureOwnedProviders(
      List<AuthenticationProvider> providers,
      OAuth2AuthorizationService authorizations,
      IdentityLookup identities,
      RefreshTokenLedger ledger,
      JdbcTemplate jdbc,
      PlatformTransactionManager manager) {
    for (int index = 0; index < providers.size(); index++) {
      var provider = providers.get(index);
      if (provider instanceof OAuth2TokenIntrospectionAuthenticationProvider
          || provider instanceof OAuth2TokenRevocationAuthenticationProvider) {
        providers.set(
            index,
            new OwnedTokenProvider(
                provider,
                authorizations,
                identities,
                ledger,
                jdbc,
                new TransactionTemplate(manager)));
      }
    }
  }

  private static void requireScopes(OAuth2ClientCredentialsAuthenticationContext context) {
    OAuth2ClientCredentialsAuthenticationToken authentication = context.getAuthentication();
    if (authentication.getScopes().isEmpty()) {
      throw new OAuth2AuthenticationException("invalid_scope");
    }
  }

  private static String policyCorrelation() {
    var attributes = RequestContextHolder.getRequestAttributes();
    Object value =
        attributes instanceof ServletRequestAttributes servlet
            ? servlet.getRequest().getAttribute(CorrelationIdFilter.ATTRIBUTE)
            : null;
    return value instanceof String id ? id : UUID.randomUUID().toString();
  }
}

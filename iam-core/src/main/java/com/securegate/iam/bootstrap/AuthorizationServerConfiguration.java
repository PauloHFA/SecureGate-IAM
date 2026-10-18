package com.securegate.iam.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securegate.iam.audit.application.port.AuditTrail;
import com.securegate.iam.authorization.application.OAuthClientService;
import com.securegate.iam.authorization.application.port.ClientRegistry;
import com.securegate.iam.authorization.infrastructure.AuthorizationJdbcDataSource;
import com.securegate.iam.authorization.infrastructure.JdbcClientRegistry;
import com.securegate.iam.authorization.presentation.OAuthFailureHandler;
import com.securegate.iam.identity.application.port.PasswordHasher;
import com.securegate.iam.shared.application.port.UnitOfWork;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncodingException;
import org.springframework.security.oauth2.server.authorization.*;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientCredentialsAuthenticationContext;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientCredentialsAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientCredentialsAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientCredentialsAuthenticationValidator;
import org.springframework.security.oauth2.server.authorization.client.*;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.token.*;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "iam.authorization.enabled", havingValue = "true")
@EnableConfigurationProperties(AuthorizationProperties.class)
public class AuthorizationServerConfiguration {
  @Bean
  @DependsOn("flywayInitializer")
  RegisteredClientRepository registeredClientRepository(DataSource dataSource) {
    return new JdbcRegisteredClientRepository(
        new JdbcTemplate(new AuthorizationJdbcDataSource(dataSource)));
  }

  @Bean
  @DependsOn("flywayInitializer")
  OAuth2AuthorizationService authorizationService(
      DataSource dataSource, RegisteredClientRepository clients) {
    return new JdbcOAuth2AuthorizationService(
        new JdbcTemplate(new AuthorizationJdbcDataSource(dataSource)), clients);
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
  OAuth2TokenGenerator<?> tokenGenerator(JwtEncoder encoder) {
    JwtGenerator generator = new JwtGenerator(encoder);
    generator.setJwtCustomizer(
        context -> {
          String audience =
              context
                  .getRegisteredClient()
                  .getClientSettings()
                  .getSetting(JdbcClientRegistry.AUDIENCE);
          if (audience == null || audience.isBlank()) {
            throw new OAuth2AuthenticationException("server_error");
          }
          context
              .getClaims()
              // JDBC's security allowlist supports ArrayList, not List.of() implementation types.
              .audience(new ArrayList<>(List.of(audience)))
              .claim("client_id", context.getRegisteredClient().getClientId());
        });
    return context -> {
      try {
        return generator.generate(context);
      } catch (JwtEncodingException exception) {
        throw new OAuth2AuthenticationException(new OAuth2Error("temporarily_unavailable"));
      }
    };
  }

  @Bean
  @Order(0)
  SecurityFilterChain authorizationSecurity(HttpSecurity http, ObjectMapper json) throws Exception {
    var failure = new OAuthFailureHandler(json);
    var server = OAuth2AuthorizationServerConfigurer.authorizationServer();
    return http.securityMatcher("/oauth2/token")
        .with(
            server,
            config ->
                config
                    .clientAuthentication(client -> client.errorResponseHandler(failure))
                    .tokenEndpoint(
                        token ->
                            token
                                .errorResponseHandler(failure)
                                .authenticationProviders(
                                    AuthorizationServerConfiguration::configureGrantProviders)))
        .authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
        .csrf(csrf -> csrf.ignoringRequestMatchers("/oauth2/token"))
        .sessionManagement(
            sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .requestCache(AbstractHttpConfigurer::disable)
        .exceptionHandling(
            errors ->
                errors.authenticationEntryPoint(
                    (request, response, exception) ->
                        failure.onAuthenticationFailure(
                            request,
                            response,
                            new OAuth2AuthenticationException("invalid_client"))))
        .build();
  }

  private static void configureGrantProviders(List<AuthenticationProvider> providers) {
    for (var provider : providers) {
      if (provider instanceof OAuth2ClientCredentialsAuthenticationProvider credentials) {
        credentials.setAuthenticationValidator(
            new OAuth2ClientCredentialsAuthenticationValidator()
                .andThen(AuthorizationServerConfiguration::requireScopes));
      }
    }
  }

  private static void requireScopes(OAuth2ClientCredentialsAuthenticationContext context) {
    OAuth2ClientCredentialsAuthenticationToken authentication = context.getAuthentication();
    if (authentication.getScopes().isEmpty()) {
      throw new OAuth2AuthenticationException("invalid_scope");
    }
  }
}

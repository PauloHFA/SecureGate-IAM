package com.securegate.iam.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securegate.iam.authorization.application.port.SigningKeys;
import com.securegate.iam.authorization.infrastructure.TransitJwtEncoder;
import com.securegate.iam.authorization.infrastructure.VaultTransitSettings;
import com.securegate.iam.authorization.infrastructure.VaultTransitSigningKeys;
import com.securegate.iam.shared.presentation.ProblemResponses;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "iam.transit.enabled", havingValue = "true")
@EnableConfigurationProperties(VaultTransitSettings.class)
public class TransitConfiguration {
  @Bean
  SigningKeys signingKeys(VaultTransitSettings settings, ObjectMapper json, MeterRegistry metrics) {
    return new VaultTransitSigningKeys(settings, json, metrics);
  }

  @Bean
  JwtEncoder jwtEncoder(SigningKeys keys) {
    return new TransitJwtEncoder(keys);
  }

  @Bean
  @Order(1)
  SecurityFilterChain jwksSecurity(HttpSecurity http, ProblemResponses problems) throws Exception {
    return http.securityMatcher("/oauth2/jwks")
        .authorizeHttpRequests(
            requests ->
                requests
                    .requestMatchers(HttpMethod.GET, "/oauth2/jwks")
                    .permitAll()
                    .anyRequest()
                    .denyAll())
        .sessionManagement(
            sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .requestCache(AbstractHttpConfigurer::disable)
        .exceptionHandling(
            errors ->
                errors
                    .authenticationEntryPoint(
                        (request, response, exception) -> problems.forbidden(request, response))
                    .accessDeniedHandler(
                        (request, response, exception) -> problems.forbidden(request, response)))
        .build();
  }
}

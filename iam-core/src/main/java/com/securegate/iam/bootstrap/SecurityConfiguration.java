package com.securegate.iam.bootstrap;

import com.securegate.iam.shared.presentation.ProblemResponses;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BootstrapAdminProperties.class)
public class SecurityConfiguration {
  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http, ProblemResponses problems, BootstrapAdminProperties bootstrap)
      throws Exception {
    if (bootstrap.enabled()) {
      http.addFilterBefore(
          new BootstrapTokenFilter(bootstrap.token(), problems),
          AnonymousAuthenticationFilter.class);
    }
    http.csrf(csrf -> csrf.ignoringRequestMatchers("/api/admin/**"));
    return http.authorizeHttpRequests(
            requests ->
                requests
                    .requestMatchers(
                        HttpMethod.GET,
                        "/actuator/health",
                        "/actuator/health/liveness",
                        "/actuator/health/readiness")
                    .permitAll()
                    .requestMatchers("/api/admin/**")
                    .hasAuthority("BOOTSTRAP_ADMIN")
                    .anyRequest()
                    .denyAll())
        .sessionManagement(
            sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .requestCache(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .headers(Customizer.withDefaults())
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

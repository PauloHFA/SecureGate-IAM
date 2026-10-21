package com.securegate.iam.bootstrap;

import com.securegate.iam.shared.presentation.ProblemResponses;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "management.endpoint.prometheus.enabled", havingValue = "true")
public class PrometheusSecurityConfiguration {
  @Bean
  @Order(4)
  SecurityFilterChain prometheusSecurity(
      HttpSecurity http,
      ProblemResponses problems,
      @Value("${IAM_METRICS_TOKEN_FILE:}") String tokenFile)
      throws Exception {
    String token = metricsToken(tokenFile);
    return http.securityMatcher("/actuator/prometheus")
        .addFilterBefore(
            new MetricsTokenFilter(token, problems), AnonymousAuthenticationFilter.class)
        .csrf(AbstractHttpConfigurer::disable)
        .requestCache(AbstractHttpConfigurer::disable)
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            r ->
                r.requestMatchers(HttpMethod.GET, "/actuator/prometheus")
                    .hasAuthority("METRICS_READ")
                    .anyRequest()
                    .denyAll())
        .httpBasic(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                        (request, response, exception) -> problems.unauthorized(request, response))
                    .accessDeniedHandler(
                        (request, response, exception) -> problems.forbidden(request, response)))
        .build();
  }

  static String metricsToken(String tokenFile) {
    String token;
    try {
      Path path = Path.of(tokenFile);
      if (tokenFile.isBlank() || !Files.isRegularFile(path) || Files.size(path) > 512) {
        throw new IOException();
      }
      token = Files.readString(path, StandardCharsets.UTF_8).strip();
      if (token.length() < 32
          || token.length() > 256
          || token.chars().anyMatch(Character::isWhitespace)) {
        throw new IOException();
      }
    } catch (IOException | RuntimeException invalid) {
      throw new IllegalStateException(
          "Prometheus requires a readable metrics token file (32-256 non-whitespace characters).");
    }
    return token;
  }

  private static byte[] digest(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("SHA-256 unavailable");
    }
  }

  private static final class MetricsTokenFilter extends OncePerRequestFilter {
    private final byte[] expected;
    private final ProblemResponses problems;

    MetricsTokenFilter(String token, ProblemResponses problems) {
      this.expected = digest(token);
      this.problems = problems;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
      var headers = Collections.list(request.getHeaders("Authorization"));
      String header = headers.size() == 1 ? headers.getFirst() : "";
      if (header.length() > 512
          || !header.regionMatches(true, 0, "Bearer ", 0, 7)
          || !MessageDigest.isEqual(expected, digest(header.substring(7)))) {
        problems.unauthorized(request, response);
        return;
      }
      var previous = SecurityContextHolder.getContext();
      var context = SecurityContextHolder.createEmptyContext();
      context.setAuthentication(
          UsernamePasswordAuthenticationToken.authenticated(
              "metrics-reader", null, List.of(new SimpleGrantedAuthority("METRICS_READ"))));
      SecurityContextHolder.setContext(context);
      try {
        chain.doFilter(request, response);
      } finally {
        SecurityContextHolder.setContext(previous);
      }
    }
  }
}

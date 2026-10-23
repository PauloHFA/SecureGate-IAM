package com.securegate.gateway;

import com.nimbusds.jwt.SignedJWT;
import com.securegate.events.SecurityProjectionKeys;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Global filter enforcing early token revocation checks at the API Gateway edge.
 *
 * <p>Consults Redis projections for token (JTI), session (sid), and account blocks. Implements a
 * strict fail-closed posture: if Redis is unavailable, protected access is refused (HTTP 503).
 */
@Component
public class TokenRevocationGatewayFilter implements GlobalFilter, Ordered {

  private static final Logger log = LoggerFactory.getLogger(TokenRevocationGatewayFilter.class);
  private static final String BEARER_PREFIX = "Bearer ";

  private final ReactiveStringRedisTemplate redis;
  private final boolean failClosed;

  public TokenRevocationGatewayFilter(
      ReactiveStringRedisTemplate redis,
      @Value("${securegate.gateway.fail-closed:true}") boolean failClosed) {
    this.redis = redis;
    this.failClosed = failClosed;
  }

  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE + 20;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    String path = exchange.getRequest().getURI().getPath();

    // Only inspect protected resource and PAM routes at the gateway
    if (!path.startsWith("/api/resources") && !path.startsWith("/api/pam")) {
      return chain.filter(exchange);
    }

    String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
    if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
      return unauthorizedResponse(exchange, "Missing or invalid Bearer authentication header");
    }

    String token = authHeader.substring(BEARER_PREFIX.length()).trim();
    String jti;
    String sub;
    String sid;

    try {
      SignedJWT signedJwt = SignedJWT.parse(token);
      var claims = signedJwt.getJWTClaimsSet();
      jti = claims.getJWTID();
      sub = claims.getSubject();
      sid = claims.getStringClaim("sid");
    } catch (Exception parseException) {
      log.warn("Malformed JWT token presented at gateway: {}", parseException.getMessage());
      return unauthorizedResponse(exchange, "Invalid JWT token structure");
    }

    if (jti == null && sid == null && sub == null) {
      return unauthorizedResponse(exchange, "JWT missing required revocation claims");
    }

    // Build the list of projection keys to inspect in Redis
    List<String> keysToCheck = new java.util.ArrayList<>();
    if (jti != null) {
      keysToCheck.add(SecurityProjectionKeys.key("revocation", "jti", jti));
    }
    if (sid != null) {
      keysToCheck.add(SecurityProjectionKeys.key("revocation", "sid", sid));
    }
    if (sub != null) {
      keysToCheck.add(SecurityProjectionKeys.key("block-policy", "blocked:account", sub + ":1"));
    }

    return Flux.fromIterable(keysToCheck)
        .flatMap(key -> redis.hasKey(key).map(hasKey -> Boolean.TRUE.equals(hasKey)))
        .any(isRevoked -> isRevoked)
        .flatMap(
            isRevoked -> {
              if (isRevoked) {
                log.warn("Refusing revoked or blocked token at API Gateway edge");
                return unauthorizedResponse(
                    exchange, "Token or session has been revoked by security policy");
              }
              // Mutate request with correlation and verification headers
              ServerWebExchange mutated =
                  exchange
                      .mutate()
                      .request(
                          builder ->
                              builder
                                  .header("X-Gateway-Verified", "true")
                                  .header(
                                      "X-Correlation-ID",
                                      exchange
                                          .getRequest()
                                          .getHeaders()
                                          .getOrDefault(
                                              "X-Correlation-ID",
                                              List.of(UUID.randomUUID().toString()))
                                          .getFirst()))
                      .build();
              return chain.filter(mutated);
            })
        .onErrorResume(
            throwable -> {
              log.error(
                  "Redis dependency failure during revocation check: {}", throwable.getMessage());
              if (failClosed) {
                return serviceUnavailableResponse(
                    exchange, "Security policy projection temporarily unavailable (Fail-Closed)");
              }
              return chain.filter(exchange);
            });
  }

  private Mono<Void> unauthorizedResponse(ServerWebExchange exchange, String detail) {
    return writeProblemJson(exchange, HttpStatus.UNAUTHORIZED, "Unauthorized", detail);
  }

  private Mono<Void> serviceUnavailableResponse(ServerWebExchange exchange, String detail) {
    return writeProblemJson(
        exchange, HttpStatus.SERVICE_UNAVAILABLE, "Service Unavailable", detail);
  }

  private Mono<Void> writeProblemJson(
      ServerWebExchange exchange, HttpStatus status, String title, String detail) {
    exchange.getResponse().setStatusCode(status);
    exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
    exchange.getResponse().getHeaders().set("Cache-Control", "no-store");

    String body =
        String.format(
            "{\"type\":\"about:blank\",\"title\":\"%s\",\"status\":%d,\"detail\":\"%s\",\"instance\":\"%s\"}",
            title, status.value(), detail, exchange.getRequest().getURI().getPath());

    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
    return exchange.getResponse().writeWith(Mono.just(buffer));
  }
}

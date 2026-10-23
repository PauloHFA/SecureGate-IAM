package com.securegate.gateway;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.util.Date;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class TokenRevocationGatewayFilterTest {

  private ReactiveStringRedisTemplate redis;
  private TokenRevocationGatewayFilter filter;
  private GatewayFilterChain chain;
  private RSAPrivateKey privateKey;

  @BeforeEach
  void setUp() throws Exception {
    redis = mock(ReactiveStringRedisTemplate.class);
    filter = new TokenRevocationGatewayFilter(redis, true);
    chain = mock(GatewayFilterChain.class);
    when(chain.filter(Mockito.any())).thenReturn(Mono.empty());

    KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
    kpg.initialize(2048);
    var keyPair = kpg.generateKeyPair();
    privateKey = (RSAPrivateKey) keyPair.getPrivate();
  }

  private String createJwt(String jti, String sub, String sid) throws Exception {
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .jwtID(jti)
            .subject(sub)
            .claim("sid", sid)
            .issuer("http://localhost:8080")
            .audience("securegate-api")
            .issueTime(new Date())
            .expirationTime(new Date(System.currentTimeMillis() + 300000))
            .build();

    SignedJWT signed = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
    signed.sign(new RSASSASigner(privateKey));
    return signed.serialize();
  }

  @Test
  void publicRoutesBypassFilterWithoutCallingRedis() {
    MockServerHttpRequest request =
        MockServerHttpRequest.get("/.well-known/openid-configuration").build();
    MockServerWebExchange exchange = MockServerWebExchange.from(request);

    StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

    verify(chain, times(1)).filter(exchange);
    verifyNoInteractions(redis);
  }

  @Test
  void protectedRouteWithoutAuthorizationHeaderReturnsUnauthorized() {
    MockServerHttpRequest request = MockServerHttpRequest.get("/api/resources/account").build();
    MockServerWebExchange exchange = MockServerWebExchange.from(request);

    StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

    assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    verifyNoInteractions(chain);
    verifyNoInteractions(redis);
  }

  @Test
  void protectedRouteWithMalformedTokenReturnsUnauthorized() {
    MockServerHttpRequest request =
        MockServerHttpRequest.get("/api/resources/account")
            .header(HttpHeaders.AUTHORIZATION, "Bearer invalid-malformed-token-string")
            .build();
    MockServerWebExchange exchange = MockServerWebExchange.from(request);

    StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

    assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    verifyNoInteractions(chain);
    verifyNoInteractions(redis);
  }

  @Test
  void activeValidTokenWithoutRevocationInRedisPassesThroughAndAddsVerificationHeader()
      throws Exception {
    String token =
        createJwt(
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString());
    MockServerHttpRequest request =
        MockServerHttpRequest.get("/api/resources/account")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .build();
    MockServerWebExchange exchange = MockServerWebExchange.from(request);

    when(redis.hasKey(anyString())).thenReturn(Mono.just(false));

    StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

    verify(chain, times(1))
        .filter(
            argThat(
                ex -> "true".equals(ex.getRequest().getHeaders().getFirst("X-Gateway-Verified"))));
  }

  @Test
  void revokedTokenInRedisIsRejectedAtEdgeWithUnauthorized() throws Exception {
    String token =
        createJwt(
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString());
    MockServerHttpRequest request =
        MockServerHttpRequest.get("/api/resources/account")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .build();
    MockServerWebExchange exchange = MockServerWebExchange.from(request);

    when(redis.hasKey(anyString())).thenReturn(Mono.just(true));

    StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

    assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    verifyNoInteractions(chain);
  }

  @Test
  void redisFailureFailsClosedReturningServiceUnavailable() throws Exception {
    String token =
        createJwt(
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString());
    MockServerHttpRequest request =
        MockServerHttpRequest.get("/api/resources/account")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .build();
    MockServerWebExchange exchange = MockServerWebExchange.from(request);

    when(redis.hasKey(anyString()))
        .thenReturn(Mono.error(new RedisConnectionFailureException("Redis connection timeout")));

    StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exchange.getResponse().getStatusCode());
    verifyNoInteractions(chain);
  }
}

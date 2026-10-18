package com.securegate.iam.authorization.infrastructure;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import com.securegate.iam.authorization.application.port.SigningKeys;
import java.util.List;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/** Verifies retained Transit public keys without a self-HTTP request or private key. */
public final class TransitPublicJwtDecoder implements JwtDecoder {
  private final NimbusJwtDecoder delegate;

  public TransitPublicJwtDecoder(SigningKeys keys, String issuer) {
    var processor = new DefaultJWTProcessor<com.nimbusds.jose.proc.SecurityContext>();
    processor.setJWSKeySelector(
        new JWSVerificationKeySelector<>(
            JWSAlgorithm.RS256,
            (selector, context) -> {
              List<JWK> publicKeys =
                  keys.publicKeys().keys().stream()
                      .<JWK>map(key -> new RSAKey.Builder(key.key()).keyID(key.id()).build())
                      .toList();
              return selector.select(new JWKSet(publicKeys));
            }));
    delegate = new NimbusJwtDecoder(processor);
    delegate.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
  }

  @Override
  public Jwt decode(String value) throws JwtException {
    try {
      return delegate.decode(value);
    } catch (JwtException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw new JwtException("Public key verification unavailable");
    }
  }
}

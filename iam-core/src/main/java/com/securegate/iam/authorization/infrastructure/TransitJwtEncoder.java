package com.securegate.iam.authorization.infrastructure;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jwt.JWTClaimsSet;
import com.securegate.iam.authorization.application.SigningUnavailableException;
import com.securegate.iam.authorization.application.port.SigningKeys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtEncodingException;

/** Spring Authorization Server integration point; no local signing implementation exists. */
public final class TransitJwtEncoder implements JwtEncoder {
  private final SigningKeys keys;

  public TransitJwtEncoder(SigningKeys keys) {
    this.keys = keys;
  }

  @Override
  public Jwt encode(JwtEncoderParameters parameters) throws JwtEncodingException {
    var requested = parameters.getJwsHeader();
    if (requested != null && !SignatureAlgorithm.RS256.equals(requested.getAlgorithm())) {
      throw new JwtEncodingException("Only RS256 is supported.");
    }
    try {
      var key = keys.publicKeys().active();
      if (requested != null
          && requested.getKeyId() != null
          && !key.id().equals(requested.getKeyId())) {
        throw new JwtEncodingException("The requested key is not active.");
      }
      var header =
          new JWSHeader.Builder(JWSAlgorithm.RS256)
              .type(JOSEObjectType.JWT)
              .keyID(key.id())
              .build();
      var claims = new JWTClaimsSet.Builder();
      parameters
          .getClaims()
          .getClaims()
          .forEach(
              (name, value) ->
                  claims.claim(
                      name, value instanceof Instant instant ? Date.from(instant) : value));
      String signingInput =
          header.toBase64URL()
              + "."
              + base64(claims.build().toString().getBytes(StandardCharsets.UTF_8));
      String compact =
          signingInput
              + "."
              + base64(keys.sign(key, signingInput.getBytes(StandardCharsets.US_ASCII)));
      return new Jwt(
          compact,
          parameters.getClaims().getIssuedAt(),
          parameters.getClaims().getExpiresAt(),
          header.toJSONObject(),
          parameters.getClaims().getClaims());
    } catch (SigningUnavailableException exception) {
      throw new JwtEncodingException("Remote signing is unavailable.");
    }
  }

  private static String base64(byte[] input) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(input);
  }
}

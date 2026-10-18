package com.securegate.iam.authorization.presentation;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.securegate.iam.authorization.application.port.SigningKeys;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "iam.transit.enabled", havingValue = "true")
public final class JwksController {
  private final SigningKeys keys;

  public JwksController(SigningKeys keys) {
    this.keys = keys;
  }

  @GetMapping(value = "/oauth2/jwks", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Map<String, Object>> jwks() {
    var publicKeys =
        keys.publicKeys().keys().stream()
            .map(
                key ->
                    new RSAKey.Builder(key.key())
                        .keyID(key.id())
                        .keyUse(KeyUse.SIGNATURE)
                        .algorithm(JWSAlgorithm.RS256)
                        .build()
                        .toJSONObject())
            .toList();
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(Map.of("keys", publicKeys));
  }
}

package com.securegate.iam.authorization.application.port;

import java.security.interfaces.RSAPublicKey;
import java.util.List;

/** No private key crosses this boundary. Signing always uses an explicit key version. */
public interface SigningKeys {
  KeySet publicKeys();

  byte[] sign(PublicKey key, byte[] signingInput);

  record PublicKey(String id, int version, RSAPublicKey key) {}

  record KeySet(PublicKey active, List<PublicKey> keys) {
    public KeySet {
      keys = List.copyOf(keys);
    }
  }
}

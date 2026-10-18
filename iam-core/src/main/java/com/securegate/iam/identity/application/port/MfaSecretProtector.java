package com.securegate.iam.identity.application.port;

import com.securegate.iam.identity.domain.TotpSecret;
import java.util.UUID;

/** Encryption binds the seed to the user and factor enrollment, preventing ciphertext swaps. */
public interface MfaSecretProtector {
  String protect(UUID userId, UUID factorId, TotpSecret secret);

  TotpSecret unprotect(UUID userId, UUID factorId, String ciphertext);

  final class Unavailable extends RuntimeException {
    public Unavailable() {
      super("MFA secret protection is unavailable.");
    }
  }
}

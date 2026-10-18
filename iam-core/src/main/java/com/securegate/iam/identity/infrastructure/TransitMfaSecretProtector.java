package com.securegate.iam.identity.infrastructure;

import com.securegate.iam.authorization.application.port.TransitEncryption;
import com.securegate.iam.identity.application.port.MfaSecretProtector;
import com.securegate.iam.identity.domain.TotpSecret;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

public final class TransitMfaSecretProtector implements MfaSecretProtector {
  private final TransitEncryption encryption;

  public TransitMfaSecretProtector(TransitEncryption encryption) {
    this.encryption = encryption;
  }

  @Override
  public String protect(UUID userId, UUID factorId, TotpSecret secret) {
    byte[] binding = binding(userId, factorId);
    if (secret == null) {
      throw new IllegalArgumentException("Invalid MFA secret.");
    }
    byte[] bytes = secret.encoded().getBytes(StandardCharsets.US_ASCII);
    try {
      return encryption.encrypt(bytes, binding);
    } catch (TransitEncryption.Unavailable exception) {
      throw new Unavailable();
    } finally {
      Arrays.fill(bytes, (byte) 0);
    }
  }

  @Override
  public TotpSecret unprotect(UUID userId, UUID factorId, String ciphertext) {
    byte[] binding = binding(userId, factorId);
    byte[] bytes = null;
    try {
      bytes = encryption.decrypt(ciphertext, binding);
      return TotpSecret.parse(new String(bytes, StandardCharsets.US_ASCII));
    } catch (TransitEncryption.Unavailable | IllegalArgumentException exception) {
      throw new Unavailable();
    } finally {
      if (bytes != null) {
        Arrays.fill(bytes, (byte) 0);
      }
    }
  }

  private byte[] binding(UUID userId, UUID factorId) {
    if (userId == null || factorId == null) {
      throw new IllegalArgumentException("Invalid MFA binding.");
    }
    return ("securegate:mfa:totp:v1:" + userId + ":" + factorId)
        .getBytes(StandardCharsets.US_ASCII);
  }
}

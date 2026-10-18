package com.securegate.iam.authorization.application.port;

/** Authenticated encryption using the dedicated non-exportable MFA Transit key. */
public interface TransitEncryption {
  String encrypt(byte[] plaintext, byte[] associatedData);

  byte[] decrypt(String ciphertext, byte[] associatedData);

  final class Unavailable extends RuntimeException {
    public Unavailable() {
      super("Remote encryption is unavailable.");
    }
  }
}

package com.securegate.iam.identity.infrastructure;

import com.securegate.iam.identity.application.port.PasswordHasher;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

public class Argon2PasswordHasher implements PasswordHasher {
  private final Argon2PasswordEncoder encoder = new Argon2PasswordEncoder(16, 32, 1, 19456, 2);

  @Override
  public String hash(String password) {
    return encoder.encode(password);
  }
}

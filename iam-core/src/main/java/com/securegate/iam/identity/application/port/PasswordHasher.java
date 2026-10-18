package com.securegate.iam.identity.application.port;

public interface PasswordHasher {
  String hash(String password);
}

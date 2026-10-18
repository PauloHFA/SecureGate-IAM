package com.securegate.iam.identity.application.port;

import java.util.UUID;

public interface CredentialRepository {
  void create(UUID userId, String hash);

  void replace(UUID userId, String hash);
}

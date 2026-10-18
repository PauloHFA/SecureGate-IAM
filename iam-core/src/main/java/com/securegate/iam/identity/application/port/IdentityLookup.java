package com.securegate.iam.identity.application.port;

import java.util.UUID;

public interface IdentityLookup {
  boolean exists(UUID userId);

  boolean isActive(UUID userId);
}

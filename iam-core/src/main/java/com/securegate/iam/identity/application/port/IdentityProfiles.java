package com.securegate.iam.identity.application.port;

import java.util.Optional;
import java.util.UUID;

/** Claims may only be disclosed for the current ACTIVE identity snapshot. */
public interface IdentityProfiles {
  Optional<Profile> findCurrent(UUID userId, long version);

  record Profile(String name, String email) {}
}

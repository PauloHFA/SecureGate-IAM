package com.securegate.enforcer.application.port;

import com.securegate.enforcer.domain.Revocation;

public interface RevocationStore {
  enum Result {
    APPLIED,
    DUPLICATE,
    CONFLICT
  }

  Result apply(Revocation revocation, String fingerprint);
}

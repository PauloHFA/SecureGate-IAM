package com.securegate.enforcer.application.port;

import com.securegate.enforcer.domain.BlockPolicyEffect;

public interface BlockPolicyStore {
  enum Result {
    APPLIED,
    DUPLICATE,
    CONFLICT
  }

  Result apply(BlockPolicyEffect effect, String fingerprint);
}

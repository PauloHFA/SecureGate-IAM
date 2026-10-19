package com.securegate.enforcer.application;

import com.securegate.enforcer.application.port.BlockPolicyStore;
import com.securegate.enforcer.domain.BlockPolicyEffect;

public final class BlockPolicyHandler {
  private final BlockPolicyStore store;

  public BlockPolicyHandler(BlockPolicyStore store) {
    this.store = store;
  }

  public BlockPolicyStore.Result apply(BlockPolicyEffect effect, String fingerprint) {
    return store.apply(effect, fingerprint);
  }
}

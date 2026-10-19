package com.securegate.enforcer.application;

import com.securegate.enforcer.application.port.RevocationStore;
import com.securegate.enforcer.domain.Revocation;

/** The store must atomically apply the terminal effect before recording completion. */
public final class RevocationHandler {
  private final RevocationStore store;

  public RevocationHandler(RevocationStore store) {
    this.store = store;
  }

  public RevocationStore.Result apply(Revocation revocation, String fingerprint) {
    return store.apply(revocation, fingerprint);
  }
}

package com.securegate.iam.identity.application;

import com.securegate.iam.access.infrastructure.persistence.ForeignRepository;

/** Deliberate violation used to verify the architecture rules. */
public class CrossContextRepositoryAccess {
  public ForeignRepository foreignRepository() {
    return null;
  }
}

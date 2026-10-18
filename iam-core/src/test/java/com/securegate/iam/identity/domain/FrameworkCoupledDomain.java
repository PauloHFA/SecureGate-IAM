package com.securegate.iam.identity.domain;

import org.springframework.stereotype.Component;

/** Deliberate violation, imported explicitly only by architecture-rule tests. */
public class FrameworkCoupledDomain {
  public Class<?> frameworkType() {
    return Component.class;
  }
}

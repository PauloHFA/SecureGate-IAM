package com.securegate.iam.identity.application;

import com.securegate.iam.access.application.port.AccessLookupPort;

/** Fixture that depends on a port instead of another context's repository. */
public class ValidCrossContextPort {
  public AccessLookupPort port() {
    return null;
  }
}

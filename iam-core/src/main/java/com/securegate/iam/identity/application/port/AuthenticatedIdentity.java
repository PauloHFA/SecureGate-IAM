package com.securegate.iam.identity.application.port;

import java.security.Principal;

/** Immutable identity snapshot carried by a successful browser authentication. */
public interface AuthenticatedIdentity extends Principal {
  long version();
}

package com.securegate.iam.authorization.infrastructure;

import org.springframework.security.oauth2.core.OAuth2AuthenticationException;

/** A policy failure must roll back any partially saved authorization or rotated token. */
public final class PolicyGrantException extends OAuth2AuthenticationException {
  public PolicyGrantException(String code) {
    super(code);
  }
}

package com.securegate.iam.authorization.application;

/** Deliberately excludes remote payloads, credentials and low-level exception messages. */
public class SigningUnavailableException extends RuntimeException {
  public SigningUnavailableException() {
    super("Remote signing is unavailable.");
  }
}

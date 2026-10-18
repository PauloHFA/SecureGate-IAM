package com.securegate.iam.shared.application;

public class ConcurrentUpdateException extends RuntimeException {
  public ConcurrentUpdateException() {
    super("Resource version changed");
  }
}

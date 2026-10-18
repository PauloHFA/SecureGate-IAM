package com.securegate.iam.shared.application;

public class ResourceNotFoundException extends RuntimeException {
  public ResourceNotFoundException() {
    super("Resource unavailable");
  }
}

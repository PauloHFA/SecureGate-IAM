package com.securegate.iam.shared.application.port;

public record OperationContext(String actor, String correlationId) {
  public OperationContext {
    if (actor == null || actor.isBlank() || correlationId == null || correlationId.isBlank()) {
      throw new IllegalArgumentException("Actor and correlation ID are required");
    }
  }
}

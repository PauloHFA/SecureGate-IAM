package com.securegate.iam.shared.application.port;

public record PageRequest(int limit, int offset) {
  public PageRequest {
    if (limit < 1 || limit > 100 || offset < 0 || offset > 10000) {
      throw new IllegalArgumentException("Page is outside the supported bounds");
    }
  }
}

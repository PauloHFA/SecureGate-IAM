package com.securegate.iam.access.domain;

import java.util.Objects;
import java.util.UUID;

public record Role(UUID id, String name) {
  public Role {
    Objects.requireNonNull(id);
    name = AccessName.normalize(name);
  }
}

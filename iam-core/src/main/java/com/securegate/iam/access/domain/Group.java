package com.securegate.iam.access.domain;

import java.util.Objects;
import java.util.UUID;

public record Group(UUID id, String name) {
  public Group {
    Objects.requireNonNull(id);
    name = AccessName.normalize(name);
  }
}

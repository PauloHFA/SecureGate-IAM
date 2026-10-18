package com.securegate.iam.identity.application;

import com.securegate.iam.identity.domain.User;
import java.util.UUID;

public record UserView(UUID id, String email, String displayName, String status, long version) {
  static UserView from(User user) {
    return new UserView(
        user.id(), user.email().value(), user.displayName(), user.status().name(), user.version());
  }
}

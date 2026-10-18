package com.securegate.iam.identity.domain;

import java.util.Objects;
import java.util.UUID;

public record User(
    UUID id, EmailAddress email, String displayName, UserStatus status, long version) {
  public User {
    Objects.requireNonNull(id);
    Objects.requireNonNull(email);
    Objects.requireNonNull(status);
    if (displayName == null || displayName.isBlank() || displayName.length() > 120 || version < 0) {
      throw new IllegalArgumentException("User profile is invalid");
    }
    displayName = displayName.trim();
  }

  public static User register(UUID id, String email, String displayName) {
    return new User(id, new EmailAddress(email), displayName, UserStatus.ACTIVE, 0);
  }

  public User profile(String email, String displayName) {
    requireMutable();
    return new User(id, new EmailAddress(email), displayName, status, version + 1);
  }

  public User transition(UserStatus next) {
    Objects.requireNonNull(next);
    if (status == next) {
      return this;
    }
    requireMutable();
    return new User(id, email, displayName, next, version + 1);
  }

  public User credentialsChanged() {
    requireMutable();
    return new User(id, email, displayName, status, version + 1);
  }

  private void requireMutable() {
    if (status == UserStatus.DISABLED) {
      throw new IllegalArgumentException("Disabled users cannot be changed");
    }
  }
}

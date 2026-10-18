package com.securegate.iam.identity.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class UserTest {
  @Test
  void registersNormalizedIdentityWithoutCredentials() {
    User user = User.register(UUID.randomUUID(), "  ADMIN@Example.test  ", "  Admin  ");
    assertEquals("admin@example.test", user.email().value());
    assertEquals("Admin", user.displayName());
    assertEquals(UserStatus.ACTIVE, user.status());
    assertEquals(0, user.version());
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"plain", "a@b", "a b@example.test", "a@@b.test", "a@b."})
  void rejectsInvalidEmail(String value) {
    assertThrows(IllegalArgumentException.class, () -> new EmailAddress(value));
  }

  @Test
  void rejectsEmailAboveTheMaximumLength() {
    assertThrows(
        IllegalArgumentException.class, () -> new EmailAddress("a".repeat(250) + "@example.test"));
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" ", "\t"})
  void rejectsMissingDisplayName(String value) {
    assertThrows(
        IllegalArgumentException.class,
        () -> User.register(UUID.randomUUID(), "a@example.test", value));
  }

  @Test
  void validatesProfileBoundariesAndRequiredFields() {
    UUID id = UUID.randomUUID();
    EmailAddress email = new EmailAddress("a@example.test");
    assertThrows(
        IllegalArgumentException.class, () -> User.register(id, email.value(), "n".repeat(121)));
    assertThrows(
        IllegalArgumentException.class, () -> new User(id, email, "Name", UserStatus.ACTIVE, -1));
    assertThrows(
        NullPointerException.class, () -> new User(null, email, "Name", UserStatus.ACTIVE, 0));
    assertThrows(
        NullPointerException.class, () -> new User(id, null, "Name", UserStatus.ACTIVE, 0));
    assertThrows(NullPointerException.class, () -> new User(id, email, "Name", null, 0));
  }

  @Test
  void updatesProfilesAndCredentialsWithIncreasingVersions() {
    User original = User.register(UUID.randomUUID(), "a@example.test", "Name");
    User changed = original.profile("b@example.test", "Updated");
    assertEquals(1, changed.version());
    assertEquals("b@example.test", changed.email().value());
    User reset = changed.credentialsChanged();
    assertEquals(2, reset.version());
    assertEquals(changed.email(), reset.email());
    assertEquals("Updated", reset.displayName());
  }

  @Test
  void supportsLockUnlockAndTerminalDisableWithoutMutatingThePreviousValue() {
    User active = User.register(UUID.randomUUID(), "a@example.test", "Name");
    assertSame(active, active.transition(UserStatus.ACTIVE));
    User locked = active.transition(UserStatus.LOCKED);
    assertEquals(1, locked.version());
    User unlocked = locked.transition(UserStatus.ACTIVE);
    assertEquals(2, unlocked.version());
    User disabled = unlocked.transition(UserStatus.DISABLED);
    assertEquals(3, disabled.version());
    assertSame(disabled, disabled.transition(UserStatus.DISABLED));
    assertThrows(IllegalArgumentException.class, () -> disabled.transition(UserStatus.ACTIVE));
    assertThrows(IllegalArgumentException.class, () -> disabled.profile("b@example.test", "New"));
    assertThrows(IllegalArgumentException.class, disabled::credentialsChanged);
    assertEquals(UserStatus.ACTIVE, active.status());
  }
}

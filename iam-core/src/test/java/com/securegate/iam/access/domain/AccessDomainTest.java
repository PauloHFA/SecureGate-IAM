package com.securegate.iam.access.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class AccessDomainTest {
  @Test
  void normalizesIdentifiersAndPermissionKeys() {
    UUID id = UUID.randomUUID();
    assertEquals("BILLING_ADMIN", new Role(id, " billing_admin ").name());
    assertEquals("FINANCE", new Group(id, " finance ").name());
    Permission permission = new Permission(id, " Invoice ", " READ ");
    assertEquals("invoice:read", permission.key());
    assertEquals(id, permission.id());
    assertEquals("invoice", permission.resource());
    assertEquals("read", permission.action());
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"1ROLE", "bad name", "*", "a-b"})
  void rejectsInvalidCatalogNames(String name) {
    assertThrows(IllegalArgumentException.class, () -> new Role(UUID.randomUUID(), name));
    assertThrows(IllegalArgumentException.class, () -> new Group(UUID.randomUUID(), name));
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"*", "invoice:read", "white space", "1read"})
  void rejectsInvalidPermissionSegments(String value) {
    assertThrows(
        IllegalArgumentException.class, () -> new Permission(UUID.randomUUID(), value, "read"));
    assertThrows(
        IllegalArgumentException.class, () -> new Permission(UUID.randomUUID(), "invoice", value));
  }

  @Test
  void enforcesIdentifierLengthAndNonNullIds() {
    assertThrows(IllegalArgumentException.class, () -> new Role(UUID.randomUUID(), "A".repeat(65)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Permission(UUID.randomUUID(), "a".repeat(65), "read"));
    assertThrows(NullPointerException.class, () -> new Role(null, "ADMIN"));
    assertThrows(NullPointerException.class, () -> new Group(null, "FINANCE"));
    assertThrows(NullPointerException.class, () -> new Permission(null, "invoice", "read"));
  }
}

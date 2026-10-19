package com.securegate.events;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SecurityProjectionKeysTest {
  @Test
  void writersAndReadersUseOpaqueStableKeysAndSeparateNamespaces() {
    String subject = "synthetic-subject";
    String first = SecurityProjectionKeys.key("revocation", "jti", subject);
    assertEquals(first, SecurityProjectionKeys.key("revocation", "jti", subject));
    assertTrue(first.matches("securegate:\\{revocation}:jti:[a-f0-9]{64}"));
    assertFalse(first.contains(subject));
    assertNotEquals(first, SecurityProjectionKeys.key("revocation", "sid", subject));
    assertNotEquals(first, SecurityProjectionKeys.key("block-policy", "identity", subject));
    assertThrows(
        IllegalArgumentException.class, () -> SecurityProjectionKeys.key("other", "jti", subject));
    assertThrows(
        IllegalArgumentException.class,
        () -> SecurityProjectionKeys.key("revocation", "event:bad:kind", subject));
    assertThrows(
        IllegalArgumentException.class,
        () -> SecurityProjectionKeys.key("revocation", "jti", null));
  }
}

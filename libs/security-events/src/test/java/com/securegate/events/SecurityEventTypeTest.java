package com.securegate.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class SecurityEventTypeTest {
  @ParameterizedTest
  @ValueSource(
      strings = {
        "auth.login.suspicious", "auth.login.failed", "token.revoked",
        "session.terminated", "policy.changed", "user.locked"
      })
  void preservesRoutingKeysFromTheSecurityContract(String routingKey) {
    assertEquals(routingKey, new SecurityEventType(routingKey).value());
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(
      strings = {
        " ",
        "revoked",
        "auth..failed",
        ".auth.failed",
        "auth.failed.",
        "Auth.failed",
        "auth.*",
        "auth.#",
        "auth. failed",
        " auth.failed",
        "auth.failed\n"
      })
  void rejectsInvalidKeysAndRabbitMqBindingWildcards(String routingKey) {
    assertThrows(IllegalArgumentException.class, () -> new SecurityEventType(routingKey));
  }

  @Test
  void enforcesTheContractLengthLimit() {
    String maximumLength = "a." + "b".repeat(126);
    assertEquals(maximumLength, new SecurityEventType(maximumLength).value());
    assertThrows(IllegalArgumentException.class, () -> new SecurityEventType(maximumLength + "b"));
  }
}

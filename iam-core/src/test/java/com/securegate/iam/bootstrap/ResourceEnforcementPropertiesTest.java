package com.securegate.iam.bootstrap;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ResourceEnforcementPropertiesTest {
  @Test
  void validatesOptInCredentialsTimeoutAndRedactsDiagnostics() {
    assertFalse(new ResourceEnforcementProperties(false, "localhost", 6379, "", 500).enabled());
    String password = java.util.UUID.randomUUID().toString();
    assertFalse(
        new ResourceEnforcementProperties(true, "localhost", 6379, password, 500)
            .toString()
            .contains(password));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ResourceEnforcementProperties(true, "localhost", 6379, "", 500));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ResourceEnforcementProperties(true, "localhost", 0, password, 500));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ResourceEnforcementProperties(true, "localhost", 6379, password, 99));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ResourceEnforcementProperties(true, "localhost", 6379, password, 2001));
  }
}

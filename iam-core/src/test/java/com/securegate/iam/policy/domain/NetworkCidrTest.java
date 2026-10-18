package com.securegate.iam.policy.domain;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NetworkCidrTest {
  @ParameterizedTest
  @ValueSource(
      strings = {
        "example.com/24",
        "127.1/8",
        "256.0.0.1/8",
        "01.0.0.1/8",
        "127.0.0.1/33",
        "::1/129",
        "fe80::1%eth0/64",
        "not-ip/8",
        "127.0.0.1/-1",
        "bad:ip/64"
      })
  void rejectsHostnamesAndInvalidLiterals(String value) {
    assertThrows(IllegalArgumentException.class, () -> new NetworkCidr(value));
  }

  @Test
  void acceptsNumericV4AndV6AndNeverResolvesUnknownRemoteInput() {
    assertEquals("127.0.0.1/8", new NetworkCidr("127.0.0.1/8").value());
    assertTrue(new NetworkCidr("2001:db8::/32").value().endsWith("/32"));
    assertEquals("127.0.0.1", NetworkCidr.address("127.0.0.1"));
    assertNotNull(NetworkCidr.address("::1"));
    assertNull(NetworkCidr.address("untrusted.example"));
    assertNull(NetworkCidr.address(null));
  }
}

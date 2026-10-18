package com.securegate.iam.authorization.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BrowserClientTest {
  private final MachineClient definition =
      new MachineClient("web-client", "Web", "api", Set.of("api:read"));

  @ParameterizedTest
  @ValueSource(
      strings = {
        "http://remote.example/callback",
        "https://example.com/callback#fragment",
        "https://user:secret@example.com/callback",
        "https://example.com/callback?dynamic=true",
        "https://example.com",
        "/callback",
        "https://example.com:99999/callback",
        "https://example.com:0/callback",
        "bad uri"
      })
  void rejectsUnsafeRedirects(String redirect) {
    assertThrows(
        IllegalArgumentException.class, () -> new BrowserClient(definition, Set.of(redirect)));
  }

  @Test
  void acceptsHttpsAndFixedLoopbackRedirectsAndCopiesTheCollection() {
    var client =
        new BrowserClient(
            definition,
            Set.of("https://client.example/callback", "http://127.0.0.1:18090/callback"));
    assertEquals(2, client.redirectUris().size());
    assertThrows(UnsupportedOperationException.class, () -> client.redirectUris().clear());
    assertThrows(IllegalArgumentException.class, () -> new BrowserClient(definition, Set.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new BrowserClient(null, Set.of("https://client.example/callback")));
  }
}

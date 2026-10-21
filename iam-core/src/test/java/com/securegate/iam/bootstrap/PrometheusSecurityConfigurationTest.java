package com.securegate.iam.bootstrap;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PrometheusSecurityConfigurationTest {
  @TempDir Path directory;

  @Test
  void rejectsMissingOversizedAndMalformedCredentialsWithSanitizedErrors() throws Exception {
    assertThrows(
        IllegalStateException.class, () -> PrometheusSecurityConfiguration.metricsToken(""));
    assertThrows(
        IllegalStateException.class,
        () -> PrometheusSecurityConfiguration.metricsToken(directory.resolve("absent").toString()));
    for (String invalid :
        java.util.List.of("", "too-short", "x".repeat(513), "x".repeat(40) + " invalid")) {
      Path file = directory.resolve("invalid");
      Files.writeString(file, invalid);
      var error =
          assertThrows(
              IllegalStateException.class,
              () -> PrometheusSecurityConfiguration.metricsToken(file.toString()));
      assertFalse(error.getMessage().contains(directory.toString()));
      assertNull(error.getCause());
    }
  }

  @Test
  void readsValidCredentialWithOneTrailingLineEnding() throws Exception {
    String token = UUID.randomUUID().toString() + UUID.randomUUID();
    Path file = directory.resolve("valid");
    Files.writeString(file, token + "\n");
    assertEquals(token, PrometheusSecurityConfiguration.metricsToken(file.toString()));
  }
}

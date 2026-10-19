package com.securegate.iam.bootstrap;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class OutboxPublisherPropertiesTest {
  @Test
  void credentialsAreRequiredOnlyWhenPublisherConfigurationIsEnabledAndAreRedacted() {
    var settings =
        new OutboxPublisherProperties(
            "127.0.0.1", 5672, "securegate", "test-user", "synthetic-password", 2000, 30, 20, 1000);
    assertFalse(settings.toString().contains("synthetic-password"));
    assertFalse(settings.toString().contains("test-user"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new OutboxPublisherProperties(
                "localhost", 5672, "securegate", "test", "", 2000, 30, 20, 1000));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new OutboxPublisherProperties(
                "localhost", 5672, "securegate", "test", "synthetic", 5000, 10, 20, 1000));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new OutboxPublisherProperties(
                "localhost", 5672, "securegate", "test", "synthetic", 2000, 30, 101, 1000));
  }
}

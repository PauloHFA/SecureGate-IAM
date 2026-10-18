package com.securegate.events;

import java.util.regex.Pattern;

/** A topic routing key shared by producers and consumers of security events. */
public record SecurityEventType(String value) {
  private static final Pattern ROUTING_KEY = Pattern.compile("[a-z][a-z0-9]*(\\.[a-z][a-z0-9]*)+");

  public SecurityEventType {
    if (value == null || value.length() > 128 || !ROUTING_KEY.matcher(value).matches()) {
      throw new IllegalArgumentException(
          "Event type must contain lowercase dot-separated segments, up to 128 characters");
    }
  }
}

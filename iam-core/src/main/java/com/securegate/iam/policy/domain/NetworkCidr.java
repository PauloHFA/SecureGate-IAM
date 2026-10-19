package com.securegate.iam.policy.domain;

import java.net.InetAddress;
import java.net.UnknownHostException;

/** Numeric literals only: no hostname resolution or configured regular expressions. */
public record NetworkCidr(String value) {
  public NetworkCidr {
    if (value == null
        || value.length() > 64
        || !value.matches("[0-9a-fA-F:.]+/(0|[1-9][0-9]{0,2})")) {
      throw new IllegalArgumentException("Invalid network range");
    }
    String address = value.substring(0, value.indexOf('/'));
    if (!address.contains(":")) {
      var parts = address.split("\\.", -1);
      if (parts.length != 4) {
        throw new IllegalArgumentException("Invalid IPv4 address");
      }
      for (String part : parts) {
        if (!part.matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(part) > 255) {
          throw new IllegalArgumentException("Invalid IPv4 address");
        }
      }
    }
    try {
      var parsed = InetAddress.getByName(address);
      int bits = Integer.parseInt(value.substring(value.indexOf('/') + 1));
      if (bits > parsed.getAddress().length * 8) {
        throw new IllegalArgumentException("Invalid network prefix");
      }
      value = parsed.getHostAddress() + "/" + bits;
    } catch (UnknownHostException failure) {
      throw new IllegalArgumentException("Invalid IP literal");
    }
  }

  public static String address(String literal) {
    if (literal == null) {
      return null;
    }
    try {
      String cidr = new NetworkCidr(literal + (literal.contains(":") ? "/128" : "/32")).value();
      return cidr.substring(0, cidr.indexOf('/'));
    } catch (IllegalArgumentException failure) {
      return null;
    }
  }
}

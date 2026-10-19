package com.securegate.enforcer.domain;

import com.securegate.events.SecurityEvent;
import com.securegate.events.SecurityEventData;
import java.util.UUID;

/** Identity/policy watermarks and bounded observations, never an ALLOW decision. */
public record BlockPolicyEffect(
    UUID eventId,
    Kind kind,
    String subject,
    String version,
    String state,
    String network,
    String attemptId,
    long occurredAtMillis) {
  public enum Kind {
    IDENTITY,
    POLICY,
    FAILURE,
    RISK
  }

  public BlockPolicyEffect {
    if (eventId == null
        || kind == null
        || subject == null
        || version == null
        || state == null
        || network == null
        || attemptId == null
        || !version.matches("0|[1-9][0-9]{0,18}")
        || Long.parseLong(version) < 0
        || occurredAtMillis < 0
        || occurredAtMillis > 253402300799999L) {
      throw new IllegalArgumentException("Invalid projection.");
    }
    if (kind == Kind.IDENTITY
        && !java.util.Set.of("ACTIVE", "LOCKED", "DISABLED").contains(state)) {
      throw new IllegalArgumentException("Invalid status.");
    }
    if (kind != Kind.FAILURE && !UUID.fromString(subject).toString().equals(subject)) {
      throw new IllegalArgumentException("Invalid subject.");
    }
    if (kind == Kind.FAILURE && !UUID.fromString(attemptId).toString().equals(attemptId)) {
      throw new IllegalArgumentException("Invalid attempt.");
    }
    if (!network.isEmpty()) {
      network = SecurityEventData.numericAddress(network);
    }
  }

  public static BlockPolicyEffect from(SecurityEvent event) {
    if (!"iam-core".equals(event.producer())) {
      throw new IllegalArgumentException("Unsupported producer.");
    }
    var data = event.data();
    String version = data.getOrDefault("version", "0");
    if (!version.matches("0|[1-9][0-9]{0,18}") || Long.parseLong(version) < 0) {
      throw new IllegalArgumentException("Invalid version.");
    }
    String address =
        data.containsKey("remoteAddress")
            ? SecurityEventData.numericAddress(data.get("remoteAddress"))
            : "";
    long occurred = event.occurredAt().toEpochMilli();
    if (occurred < 0 || occurred > 253402300799999L) {
      throw new IllegalArgumentException("Invalid occurrence time.");
    }
    return switch (event.eventType()) {
      case "identity.user.created",
          "identity.user.updated",
          "identity.user.statuschanged",
          "identity.user.passwordchanged" -> {
        String id = event.context().get("resource");
        if (!UUID.fromString(id).toString().equals(id)) {
          throw new IllegalArgumentException("Invalid identity.");
        }
        yield new BlockPolicyEffect(
            event.eventId(), Kind.IDENTITY, id, version, data.get("status"), "", "", occurred);
      }
      case "user.locked" ->
          new BlockPolicyEffect(
              event.eventId(),
              Kind.IDENTITY,
              data.get("userId"),
              version,
              "LOCKED",
              "",
              "",
              occurred);
      case "policy.changed" ->
          new BlockPolicyEffect(
              event.eventId(),
              Kind.POLICY,
              data.get("policyId"),
              version,
              data.get("enabled") + "|" + data.get("resource") + "\u0000" + data.get("action"),
              "",
              "",
              occurred);
      case "auth.login.failed" ->
          new BlockPolicyEffect(
              event.eventId(),
              Kind.FAILURE,
              data.containsKey("userId") ? data.get("userId") + ":" + version : "",
              version,
              "",
              address,
              data.get("attemptId"),
              occurred);
      case "auth.login.suspicious" ->
          new BlockPolicyEffect(
              event.eventId(), Kind.RISK, data.get("sid"), version, "", address, "", occurred);
      default -> throw new IllegalArgumentException("Unsupported block/policy event.");
    };
  }

  @Override
  public String toString() {
    return "BlockPolicyEffect[kind=" + kind + ", metadata=redacted]";
  }
}

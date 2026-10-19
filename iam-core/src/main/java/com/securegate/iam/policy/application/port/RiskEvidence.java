package com.securegate.iam.policy.application.port;

import com.securegate.iam.policy.domain.RiskSignals;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface RiskEvidence extends RiskContextSink {
  RiskSignals collect(UUID userId, String resource, UUID contextId);

  List<ContextView> contexts(UUID userId);

  ResourceView createResource(String resource, String sensitivity);

  ResourceView updateResource(String resource, String sensitivity, long version);

  ResourceView resource(String resource);

  NetworkView createNetwork(UUID id, String cidr);

  NetworkView updateNetwork(UUID id, boolean enabled, long version);

  List<NetworkView> networks();

  DeviceView device(UUID userId, UUID deviceId);

  DeviceView trustDevice(UUID userId, UUID deviceId, boolean trusted, int ttlSeconds, long version);

  record ContextView(
      UUID id, UUID deviceId, Instant observedAt, long deviceVersion, Instant trustedUntil) {}

  record DeviceView(UUID deviceId, long version, Instant trustedUntil) {}

  record ResourceView(String resource, String sensitivity, long version) {}

  record NetworkView(UUID id, String cidr, boolean enabled, long version) {}
}

package com.securegate.iam.policy.application.port;

import java.util.UUID;

/**
 * Internal authenticated browser producer; never bind this port to a public JSON ingestion
 * endpoint.
 */
public interface RiskContextSink {
  UUID observe(
      UUID userId, long identityVersion, UUID contextId, String deviceHint, String remoteAddress);

  void close(UUID userId, UUID contextId);
}

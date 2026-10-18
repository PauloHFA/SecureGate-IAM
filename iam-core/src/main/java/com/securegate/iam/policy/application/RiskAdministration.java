package com.securegate.iam.policy.application;

import com.securegate.events.SecurityEventType;
import com.securegate.iam.audit.application.port.AuditRecord;
import com.securegate.iam.audit.application.port.AuditTrail;
import com.securegate.iam.identity.application.port.IdentityLookup;
import com.securegate.iam.policy.application.port.RiskEvidence;
import com.securegate.iam.policy.domain.EvaluationContext;
import com.securegate.iam.policy.domain.NetworkCidr;
import com.securegate.iam.policy.domain.RiskSignals;
import com.securegate.iam.shared.application.ResourceNotFoundException;
import com.securegate.iam.shared.application.port.OperationContext;
import com.securegate.iam.shared.application.port.UnitOfWork;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class RiskAdministration {
  private final RiskEvidence evidence;
  private final IdentityLookup identities;
  private final UnitOfWork transactions;
  private final AuditTrail audit;

  public RiskAdministration(
      RiskEvidence evidence, IdentityLookup identities, UnitOfWork transactions, AuditTrail audit) {
    this.evidence = evidence;
    this.identities = identities;
    this.transactions = transactions;
    this.audit = audit;
  }

  private static void resourceTarget(String resource) {
    new EvaluationContext(resource, "read", Set.of(), Set.of("risk:read"));
  }

  private static void sensitivity(String value) {
    if (value == null
        || RiskSignals.Sensitivity.valueOf(value) == RiskSignals.Sensitivity.UNKNOWN) {
      throw new IllegalArgumentException("Sensitivity must be classified");
    }
  }

  public RiskEvidence.ResourceView createResource(
      String resource, String value, OperationContext actor) {
    resourceTarget(resource);
    sensitivity(value);
    return transactions.execute(
        () -> {
          var result = evidence.createResource(resource, value);
          record(
              "risk.resource.created",
              UUID.nameUUIDFromBytes(resource.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                  .toString(),
              result.version(),
              actor);
          return result;
        });
  }

  public RiskEvidence.ResourceView updateResource(
      String resource, String value, long version, OperationContext actor) {
    resourceTarget(resource);
    sensitivity(value);
    return transactions.execute(
        () -> {
          var result = evidence.updateResource(resource, value, version);
          record(
              "risk.resource.updated",
              UUID.nameUUIDFromBytes(resource.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                  .toString(),
              result.version(),
              actor);
          return result;
        });
  }

  public RiskEvidence.ResourceView resource(String resource) {
    resourceTarget(resource);
    return evidence.resource(resource);
  }

  public RiskEvidence.NetworkView createNetwork(String cidr, OperationContext actor) {
    String network = new NetworkCidr(cidr).value();
    return transactions.execute(
        () -> {
          var result = evidence.createNetwork(UUID.randomUUID(), network);
          record("risk.network.created", result.id().toString(), result.version(), actor);
          return result;
        });
  }

  public RiskEvidence.NetworkView updateNetwork(
      UUID id, boolean enabled, long version, OperationContext actor) {
    return transactions.execute(
        () -> {
          var result = evidence.updateNetwork(id, enabled, version);
          record("risk.network.updated", id.toString(), result.version(), actor);
          return result;
        });
  }

  public List<RiskEvidence.NetworkView> networks() {
    return evidence.networks();
  }

  public List<RiskEvidence.ContextView> contexts(UUID user) {
    requireUser(user);
    return evidence.contexts(user);
  }

  public RiskEvidence.DeviceView device(UUID user, UUID device) {
    requireUser(user);
    return evidence.device(user, device);
  }

  public RiskEvidence.DeviceView trustDevice(
      UUID user, UUID device, boolean trusted, int ttl, long version, OperationContext actor) {
    requireUser(user);
    if (trusted && !identities.isActive(user) || ttl < 60 || ttl > 86400) {
      throw new IllegalArgumentException("Invalid browser trust approval");
    }
    return transactions.execute(
        () -> {
          var result = evidence.trustDevice(user, device, trusted, ttl, version);
          record(
              trusted ? "risk.device.approved" : "risk.device.revoked",
              device.toString(),
              result.version(),
              actor);
          return result;
        });
  }

  private void requireUser(UUID user) {
    if (!identities.exists(user)) {
      throw new ResourceNotFoundException();
    }
  }

  private void record(String type, String resource, long version, OperationContext actor) {
    audit.record(
        new AuditRecord(
            new SecurityEventType(type),
            resource,
            actor,
            Map.of("version", Long.toString(version))));
  }
}

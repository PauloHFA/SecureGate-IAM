package com.securegate.iam.authorization.application;

import com.securegate.events.SecurityEventType;
import com.securegate.iam.audit.application.port.AuditRecord;
import com.securegate.iam.audit.application.port.AuditTrail;
import com.securegate.iam.authorization.application.port.PrivilegedRequests;
import com.securegate.iam.shared.application.ResourceNotFoundException;
import com.securegate.iam.shared.application.port.OperationContext;
import com.securegate.iam.shared.application.port.UnitOfWork;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** A recorded authorization request is not a credential or a reusable access capability. */
public final class PrivilegedRequestService {
  private final PrivilegedRequests requests;
  private final UnitOfWork transactions;
  private final AuditTrail audit;
  private final Clock clock;

  public PrivilegedRequestService(
      PrivilegedRequests requests, UnitOfWork transactions, AuditTrail audit, Clock clock) {
    this.requests = requests;
    this.transactions = transactions;
    this.audit = audit;
    this.clock = clock;
  }

  public View create(
      ResourceAccessService.Permit permit,
      String justification,
      int ttlSeconds,
      String correlationId) {
    if (justification == null
        || justification.strip().length() < 10
        || justification.strip().length() > 500
        || justification.codePoints().anyMatch(Character::isISOControl)
        || ttlSeconds < 30
        || ttlSeconds > 300) {
      throw new IllegalArgumentException("Invalid privileged request.");
    }
    Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    Instant expiry = now.plusSeconds(ttlSeconds);
    if (permit.expiresAt().isBefore(expiry)) {
      expiry = permit.expiresAt();
    }
    expiry = expiry.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    if (!expiry.isAfter(now)) {
      throw new ResourceAccessService.Rejected("insufficient_scope");
    }
    var entry =
        new PrivilegedRequests.Entry(
            UUID.randomUUID(),
            permit.userId(),
            "database:diagnostics",
            justification.strip(),
            now,
            expiry,
            null,
            correlationId);
    return transactions.execute(
        () -> {
          requests.insert(entry);
          record("pam.request.authorized", entry, correlationId);
          return view(entry);
        });
  }

  public View get(UUID id, UUID userId) {
    return view(required(id, userId));
  }

  public View revoke(UUID id, UUID userId, String correlationId) {
    return transactions.execute(
        () -> {
          required(id, userId);
          if (requests.revoke(id, userId, clock.instant())) {
            record("pam.request.revoked", required(id, userId), correlationId);
          }
          return view(required(id, userId));
        });
  }

  private PrivilegedRequests.Entry required(UUID id, UUID userId) {
    return requests.find(id, userId).orElseThrow(ResourceNotFoundException::new);
  }

  public void observeExpirations() {
    Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    for (var entry : requests.expired(now)) {
      transactions.execute(
          () -> {
            if (requests.recordExpiration(entry.id(), now)) {
              audit.record(
                  new AuditRecord(
                      new SecurityEventType("pam.request.expired"),
                      entry.target(),
                      new OperationContext("pam-maintenance", entry.originCorrelationId()),
                      Map.of(
                          "requestId",
                          entry.id().toString(),
                          "expiresAt",
                          entry.expiresAt().toString(),
                          "meaning",
                          "LOGICAL_DEADLINE")));
            }
            return null;
          });
    }
  }

  public View revokeAdministratively(UUID id, OperationContext operator) {
    return transactions.execute(
        () -> {
          var entry = requests.forAdministration(id).orElseThrow(ResourceNotFoundException::new);
          // Administrative revocation also queues cleanup after logical expiry.
          if (requests.revoke(id, entry.userId(), clock.instant())) {
            audit.record(
                new AuditRecord(
                    new SecurityEventType("pam.request.revoked"),
                    entry.target(),
                    operator,
                    Map.of("requestId", id.toString())));
          }
          return view(required(id, entry.userId()));
        });
  }

  private View view(PrivilegedRequests.Entry entry) {
    String state =
        entry.revokedAt() != null
            ? "REVOKED"
            : !entry.expiresAt().isAfter(clock.instant()) ? "EXPIRED" : "AUTHORIZED";
    return new View(
        entry.id(), entry.target(), state, entry.createdAt(), entry.expiresAt(), entry.revokedAt());
  }

  private void record(String type, PrivilegedRequests.Entry entry, String correlationId) {
    audit.record(
        new AuditRecord(
            new SecurityEventType(type),
            entry.target(),
            new OperationContext(entry.userId().toString(), correlationId),
            Map.of("requestId", entry.id().toString(), "expiresAt", entry.expiresAt().toString())));
  }

  public record View(
      UUID id,
      String target,
      String status,
      Instant createdAt,
      Instant expiresAt,
      Instant revokedAt) {}
}

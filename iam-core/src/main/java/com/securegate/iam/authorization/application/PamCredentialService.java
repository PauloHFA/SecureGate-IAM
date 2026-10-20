package com.securegate.iam.authorization.application;

import com.securegate.events.SecurityEventType;
import com.securegate.iam.audit.application.port.AuditRecord;
import com.securegate.iam.audit.application.port.AuditTrail;
import com.securegate.iam.authorization.application.port.*;
import com.securegate.iam.shared.application.ConcurrentUpdateException;
import com.securegate.iam.shared.application.ResourceNotFoundException;
import com.securegate.iam.shared.application.port.OperationContext;
import com.securegate.iam.shared.application.port.UnitOfWork;
import java.time.*;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;

public final class PamCredentialService {
  private final PrivilegedRequests requests;
  private final PrivilegedIssuances issuances;
  private final PamCredentials vault;
  private final ResourceAccessService access;
  private final UnitOfWork transactions;
  private final AuditTrail audit;
  private final Clock clock;
  private final PamAudit pamAudit;
  private final Semaphore admission = new Semaphore(4);

  public PamCredentialService(
      PrivilegedRequests requests,
      PrivilegedIssuances issuances,
      PamCredentials vault,
      ResourceAccessService access,
      UnitOfWork transactions,
      AuditTrail audit,
      Clock clock) {
    this.requests = requests;
    this.issuances = issuances;
    this.vault = vault;
    this.access = access;
    this.transactions = transactions;
    this.audit = audit;
    this.clock = clock;
    this.pamAudit = new PamAudit(audit);
  }

  public Secret issue(UUID id, String token, String remote, String correlation) {
    ResourceAccessService.Permit permit;
    try {
      permit = access.authorize(token, ResourceAccessService.Operation.PRIVILEGED_ISSUE, remote);
    } catch (ResourceAccessService.Rejected rejected) {
      try {
        pamAudit.denied(
            rejected.userId(),
            correlation,
            ResourceAccessService.Operation.PRIVILEGED_ISSUE,
            rejected.code(),
            PamAudit.Stage.AUTHORIZATION);
      } catch (RuntimeException failure) {
        throw new Unavailable();
      }
      throw rejected;
    }
    if (!admission.tryAcquire()) {
      throw new Unavailable();
    }
    PamCredentials.Issued issued = null;
    Instant expiry = null;
    boolean claimed = false;
    try {
      Instant limit =
          transactions.execute(
              () -> {
                var request = required(id, permit.userId());
                Instant deadline = deadline(request, permit);
                bucket(deadline);
                if (!issuances.start(id, clock.instant(), correlation)) {
                  throw new ConcurrentUpdateException();
                }
                return deadline;
              });
      claimed = true;
      issued = vault.issue(bucket(limit));
      expiry =
          clock
              .instant()
              .plusSeconds(issued.ttlSeconds())
              .truncatedTo(java.time.temporal.ChronoUnit.MICROS);
      var confirmed =
          access.authorize(token, ResourceAccessService.Operation.PRIVILEGED_ISSUE, remote);
      if (!confirmed.userId().equals(permit.userId())) {
        throw new ResourceAccessService.Rejected("invalid_token");
      }
      var credential = issued;
      var until = expiry;
      Secret result =
          transactions.execute(
              () -> {
                var request = required(id, confirmed.userId());
                Instant currentLimit = deadline(request, confirmed);
                if (until.isAfter(currentLimit) || !until.isAfter(clock.instant().plusSeconds(1))) {
                  throw new ResourceAccessService.Rejected("insufficient_scope");
                }
                if (!issuances.finish(id, credential.leaseId(), until)) {
                  throw new ConcurrentUpdateException();
                }
                record(
                    "pam.credential.issued",
                    id,
                    confirmed.userId().toString(),
                    correlation,
                    Map.of("expiresAt", until.toString(), "meaning", "ISSUANCE_COMMITTED"));
                return new Secret(id, credential.username(), credential.password(), until);
              });
      issued.delivered();
      return result;
    } catch (RuntimeException failure) {
      if (claimed) {
        var credential = issued;
        var until = expiry;
        try {
          transactions.execute(
              () -> {
                if (issuances.abort(id, credential == null ? null : credential.leaseId(), until)) {
                  record(
                      "pam.credential.failed",
                      id,
                      permit.userId().toString(),
                      correlation,
                      Map.of(
                          "reason",
                          credential == null ? "ISSUANCE_AMBIGUOUS" : "DELIVERY_ABORTED"));
                }
                return null;
              });
        } catch (RuntimeException ignored) {
          /* Stale claims remain consumed and are recovered by maintenance. */
        }
      }
      if (failure instanceof ResourceAccessService.Rejected
          || failure instanceof ConcurrentUpdateException
          || failure instanceof ResourceNotFoundException) {
        String code =
            failure instanceof ResourceAccessService.Rejected rejected
                ? rejected.code()
                : failure instanceof ResourceNotFoundException
                    ? "request_unavailable"
                    : "request_conflict";
        try {
          pamAudit.denied(
              permit.userId(),
              correlation,
              ResourceAccessService.Operation.PRIVILEGED_ISSUE,
              code,
              issued == null ? PamAudit.Stage.CLAIM : PamAudit.Stage.CONFIRMATION);
        } catch (RuntimeException auditFailure) {
          throw new Unavailable();
        }
        throw failure;
      }
      throw new Unavailable();
    } finally {
      if (issued != null) {
        try {
          issued.close();
        } catch (RuntimeException ignored) {
          /* Durable lease cleanup or bounded Vault expiry. */
        }
      }
      admission.release();
    }
  }

  private PrivilegedRequests.Entry required(UUID id, UUID user) {
    return requests.lock(id, user).orElseThrow(ResourceNotFoundException::new);
  }

  private Instant deadline(PrivilegedRequests.Entry request, ResourceAccessService.Permit permit) {
    if (request.revokedAt() != null || !request.expiresAt().isAfter(clock.instant())) {
      throw new ConcurrentUpdateException();
    }
    return request.expiresAt().isBefore(permit.expiresAt())
        ? request.expiresAt()
        : permit.expiresAt();
  }

  private int bucket(Instant deadline) {
    long remaining = Duration.between(clock.instant(), deadline).getSeconds() - 5;
    for (int ttl : new int[] {240, 120, 60, 30, 10}) {
      if (ttl <= remaining) {
        return ttl;
      }
    }
    throw new ResourceAccessService.Rejected("insufficient_scope");
  }

  public Metadata get(UUID id, UUID user) {
    requests.find(id, user).orElseThrow(ResourceNotFoundException::new);
    var entry = issuances.find(id).orElseThrow(ResourceNotFoundException::new);
    return new Metadata(id, entry.state(), entry.expiresAt());
  }

  /** Duplicate revocations across instances are safe; the final audit transition is conditional. */
  public void maintain() {
    RuntimeException unavailable = null;
    for (var entry : issuances.pending(clock.instant())) {
      try {
        if (entry.state().equals("ISSUING")) {
          transactions.execute(
              () -> {
                if (issuances.abandoned(entry.requestId(), clock.instant().minusSeconds(30))) {
                  record(
                      "pam.credential.failed",
                      entry.requestId(),
                      "pam-maintenance",
                      entry.originCorrelationId(),
                      Map.of("reason", "CLAIM_ABANDONED"));
                }
                return null;
              });
        } else {
          transactions.execute(
              () -> {
                Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
                if (issuances.recordExpiration(entry.requestId(), now)) {
                  record(
                      "pam.credential.expired",
                      entry.requestId(),
                      "pam-maintenance",
                      entry.originCorrelationId(),
                      Map.of(
                          "expiresAt",
                          entry.expiresAt().toString(),
                          "meaning",
                          "LOGICAL_DEADLINE"));
                }
                return null;
              });
          if (entry.state().equals("ISSUED")
              && !transactions.execute(() -> issuances.revoking(entry.requestId()))) {
            continue;
          }
          vault.revoke(entry.leaseId());
          transactions.execute(
              () -> {
                if (issuances.revoked(entry.requestId())) {
                  record(
                      "pam.credential.revoked",
                      entry.requestId(),
                      "pam-maintenance",
                      entry.originCorrelationId(),
                      Map.of("meaning", "VAULT_ACKNOWLEDGED"));
                }
                return null;
              });
        }
      } catch (RuntimeException failure) {
        unavailable = failure;
      }
    }
    if (unavailable != null) {
      throw unavailable;
    }
  }

  private void record(
      String type, UUID id, String actor, String correlation, Map<String, String> details) {
    var data = new java.util.HashMap<>(details);
    data.put("requestId", id.toString());
    audit.record(
        new AuditRecord(
            new SecurityEventType(type),
            "database:diagnostics",
            new OperationContext(actor, correlation),
            data));
  }

  public record Metadata(UUID requestId, String state, Instant expiresAt) {}

  public record Secret(UUID requestId, String username, String password, Instant expiresAt) {
    @Override
    public String toString() {
      return "PamCredential[redacted]";
    }
  }

  public static final class Unavailable extends RuntimeException {
    public Unavailable() {
      super("PAM unavailable.");
    }
  }
}

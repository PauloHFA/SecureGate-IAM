package com.securegate.iam.authorization.application;

import com.securegate.iam.authorization.application.port.ResourceEnforcement;
import com.securegate.iam.authorization.application.port.ResourceTokens;
import com.securegate.iam.identity.application.port.IdentityLookup;
import com.securegate.iam.identity.application.port.MfaProofs;
import com.securegate.iam.policy.application.port.PolicyDecisions;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Every operation reevaluates live policy. A permit is valid only for the current request. */
public final class ResourceAccessService {
  private final ResourceTokens tokens;
  private final IdentityLookup identities;
  private final PolicyDecisions policies;
  private final MfaProofs proofs;
  private final Clock clock;
  private final String audience;
  private final ResourceEnforcement enforcement;

  public ResourceAccessService(
      ResourceTokens tokens,
      IdentityLookup identities,
      PolicyDecisions policies,
      MfaProofs proofs,
      Clock clock,
      String audience) {
    this(tokens, identities, policies, proofs, clock, audience, ResourceEnforcement.DISABLED);
  }

  public ResourceAccessService(
      ResourceTokens tokens,
      IdentityLookup identities,
      PolicyDecisions policies,
      MfaProofs proofs,
      Clock clock,
      String audience,
      ResourceEnforcement enforcement) {
    this.tokens = tokens;
    this.identities = identities;
    this.policies = policies;
    this.proofs = proofs;
    this.clock = clock;
    this.audience = audience;
    this.enforcement = enforcement;
  }

  public Permit authorize(String token, Operation operation) {
    return authorize(token, operation, null);
  }

  public Permit authorize(String token, Operation operation, String remoteAddress) {
    if (token == null || token.isBlank() || token.length() > 16384) {
      throw new Rejected("invalid_token");
    }
    var subject = tokens.resolve(token, audience).orElseThrow(() -> new Rejected("invalid_token"));
    Instant now = clock.instant();
    if (subject.userId() == null
        || subject.sid() == null
        || subject.jti() == null
        || subject.jti().isBlank()
        || subject.issuedAt() == null
        || subject.expiresAt() == null
        || subject.issuedAt().isAfter(now)
        || !subject.expiresAt().isAfter(now)
        || !identities.isCurrent(subject.userId(), subject.version())) {
      throw new Rejected("invalid_token");
    }
    if (!enforcement.requestAllowed(subject.userId(), subject.version(), remoteAddress)) {
      throw new Rejected("rate_limited");
    }
    var decision =
        policies.decide(
            subject.userId(),
            operation.resource,
            operation.action,
            subject.scopes(),
            subject.sid());
    var scopes = new HashSet<>(subject.scopes());
    scopes.retainAll(decision.scopes());
    if (decision.denied()
        || !scopes.contains(operation.scope)
        || decision.ttlSeconds() < 30
        || decision.ttlSeconds() > 300) {
      throw new Rejected("insufficient_scope");
    }
    if (!enforcement.policiesCurrent(decision.policies())) {
      throw new Rejected("insufficient_scope");
    }
    Instant deadline = subject.issuedAt().plusSeconds(decision.ttlSeconds());
    if (subject.expiresAt().isBefore(deadline)) {
      deadline = subject.expiresAt();
    }
    if (!deadline.isAfter(now)) {
      throw new Rejected("insufficient_scope");
    }
    if (decision.mfaRequired() || operation == Operation.PRIVILEGED_REQUEST) {
      var proof =
          proofs
              .current(subject.userId(), subject.version(), subject.sid())
              .orElseThrow(() -> new Rejected("mfa_required"));
      if (!proof.expiresAt().isAfter(now)) {
        throw new Rejected("mfa_required");
      }
      if (proof.expiresAt().isBefore(deadline)) {
        deadline = proof.expiresAt();
      }
    }
    return new Permit(subject.userId(), Set.copyOf(scopes), deadline, decision);
  }

  /**
   * Mappings are server-owned. Clients cannot choose policy targets, actions or required scopes.
   */
  public enum Operation {
    ACCOUNT_READ("account:self", "read", "api:read"),
    PRIVILEGED_REQUEST("database:diagnostics", "request", "pam:request");

    private final String resource;
    private final String action;
    private final String scope;

    Operation(String resource, String action, String scope) {
      this.resource = resource;
      this.action = action;
      this.scope = scope;
    }
  }

  public record Permit(
      UUID userId, Set<String> scopes, Instant expiresAt, PolicyDecisions.Decision decision) {}

  public static final class Rejected extends RuntimeException {
    private final String code;

    public Rejected(String code) {
      super("Resource access rejected.");
      this.code = code;
    }

    public String code() {
      return code;
    }
  }
}

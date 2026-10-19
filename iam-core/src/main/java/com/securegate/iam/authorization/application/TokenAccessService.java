package com.securegate.iam.authorization.application;

import com.securegate.iam.authorization.application.port.AuthenticationEnforcement;
import com.securegate.iam.identity.application.port.IdentityLookup;
import com.securegate.iam.identity.application.port.MfaProofs;
import com.securegate.iam.policy.application.port.PolicyDecisions;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Policy limits can only reduce the scopes already validated by the OAuth provider. */
public final class TokenAccessService {
  private final PolicyDecisions policies;
  private final IdentityLookup identities;
  private final MfaProofs proofs;
  private final AuthenticationEnforcement enforcement;

  public TokenAccessService(PolicyDecisions policies, IdentityLookup identities, MfaProofs proofs) {
    this(policies, identities, proofs, AuthenticationEnforcement.DISABLED);
  }

  public TokenAccessService(
      PolicyDecisions policies,
      IdentityLookup identities,
      MfaProofs proofs,
      AuthenticationEnforcement enforcement) {
    this.policies = policies;
    this.identities = identities;
    this.proofs = proofs;
    this.enforcement = enforcement;
  }

  public Permit authorize(
      UUID userId, long version, UUID sid, String clientId, Set<String> grantedScopes) {
    Permit permit = evaluate(userId, version, sid, clientId, grantedScopes);
    Instant deadline = null;
    if (permit.decision().mfaRequired()) {
      deadline = proofs.current(userId, version, sid).orElseThrow(Denied::new).expiresAt();
    }
    return new Permit(
        userId,
        permit.resource(),
        permit.scopes(),
        permit.ttlSeconds(),
        deadline,
        permit.decision());
  }

  /** Guidance for browser navigation only. Issuance always evaluates again. */
  public Navigation navigation(
      UUID userId, long version, UUID sid, String clientId, Set<String> scopes) {
    try {
      var permit = evaluate(userId, version, sid, clientId, scopes);
      return permit.decision().mfaRequired() && proofs.current(userId, version, sid).isEmpty()
          ? Navigation.MFA_REQUIRED
          : Navigation.READY;
    } catch (Denied exception) {
      return Navigation.DENIED;
    }
  }

  private Permit evaluate(
      UUID userId, long version, UUID sid, String clientId, Set<String> grantedScopes) {
    if (userId == null
        || sid == null
        || clientId == null
        || !clientId.matches("[a-z][a-z0-9_-]{2,63}")
        || grantedScopes == null
        || grantedScopes.isEmpty()
        || !identities.isCurrent(userId, version)) {
      throw new Denied();
    }
    String resource = "oauth:" + clientId;
    if (!enforcement.sessionAllowed(userId, version, sid)) {
      throw new Denied();
    }
    var decision = policies.decide(userId, resource, "issue", grantedScopes, sid);
    var scopes = new HashSet<>(grantedScopes);
    scopes.retainAll(decision.scopes());
    if (decision.denied()
        || scopes.isEmpty()
        || decision.ttlSeconds() < 30
        || decision.ttlSeconds() > 300
        || (grantedScopes.contains("openid") && !scopes.contains("openid"))) {
      throw new Denied();
    }
    if (!enforcement.policiesCurrent(decision.policies())) {
      throw new Denied();
    }
    if (enforcement.riskObserved(sid) && !decision.mfaRequired()) {
      decision =
          new PolicyDecisions.Decision(
              decision.denied(),
              decision.scopes(),
              decision.ttlSeconds(),
              true,
              decision.policies(),
              decision.riskScore());
    }
    return new Permit(userId, resource, Set.copyOf(scopes), decision.ttlSeconds(), null, decision);
  }

  public enum Navigation {
    READY,
    MFA_REQUIRED,
    DENIED
  }

  public record Permit(
      UUID userId,
      String resource,
      Set<String> scopes,
      int ttlSeconds,
      Instant mfaDeadline,
      PolicyDecisions.Decision decision) {}

  public static final class Denied extends RuntimeException {
    public Denied() {
      super("Token issuance denied.");
    }
  }
}

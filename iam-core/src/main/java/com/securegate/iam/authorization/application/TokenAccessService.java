package com.securegate.iam.authorization.application;

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

  public TokenAccessService(PolicyDecisions policies, IdentityLookup identities, MfaProofs proofs) {
    this.policies = policies;
    this.identities = identities;
    this.proofs = proofs;
  }

  public Permit authorize(
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
    Instant deadline = null;
    if (decision.mfaRequired()) {
      deadline = proofs.current(userId, version, sid).orElseThrow(Denied::new).expiresAt();
    }
    return new Permit(
        userId, resource, Set.copyOf(scopes), decision.ttlSeconds(), deadline, decision);
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

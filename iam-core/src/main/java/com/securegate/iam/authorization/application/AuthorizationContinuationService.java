package com.securegate.iam.authorization.application;

import com.securegate.iam.authorization.application.port.AuthorizationContinuation;
import com.securegate.iam.authorization.application.port.ContinuationStore;
import com.securegate.iam.authorization.application.port.ContinuationStore.Pending;
import com.securegate.iam.identity.application.port.IdentityLookup;
import com.securegate.iam.identity.application.port.MfaProofs;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class AuthorizationContinuationService implements AuthorizationContinuation {
  private final ContinuationStore store;
  private final IdentityLookup identities;
  private final MfaProofs proofs;
  private final Clock clock;

  public AuthorizationContinuationService(
      ContinuationStore store, IdentityLookup identities, MfaProofs proofs, Clock clock) {
    this.store = store;
    this.identities = identities;
    this.proofs = proofs;
    this.clock = clock;
  }

  @Override
  public void begin(
      UUID user, long version, UUID sid, String clientName, Map<String, String> parameters) {
    if (user == null
        || sid == null
        || !identities.isCurrent(user, version)
        || clientName == null
        || clientName.length() > 120
        || parameters == null
        || parameters.size() > 10
        || parameters.entrySet().stream()
            .anyMatch(
                entry ->
                    !java.util.Set.of(
                                "response_type",
                                "client_id",
                                "redirect_uri",
                                "scope",
                                "state",
                                "code_challenge",
                                "code_challenge_method",
                                "nonce",
                                "prompt")
                            .contains(entry.getKey())
                        || entry.getValue() == null
                        || entry.getValue().length() > 1024)
        || !parameters
            .keySet()
            .containsAll(
                java.util.Set.of(
                    "response_type",
                    "client_id",
                    "redirect_uri",
                    "scope",
                    "state",
                    "code_challenge",
                    "code_challenge_method"))
        || !"code".equals(parameters.get("response_type"))
        || !"S256".equals(parameters.get("code_challenge_method"))
        || !parameters.get("state").matches("[A-Za-z0-9._~-]{16,128}")
        || !parameters.get("code_challenge").matches("[A-Za-z0-9_-]{43}")) {
      throw new Unavailable();
    }
    store.save(
        new Pending(
            UUID.randomUUID().toString(),
            user,
            version,
            sid,
            clientName,
            parameters,
            clock.instant().plusSeconds(300)));
  }

  @Override
  public Optional<View> current(UUID user, long version, UUID sid) {
    return matching(user, version, sid)
        .map(
            pending ->
                new View(
                    pending.ticket(),
                    pending.clientName(),
                    pending.expiresAt(),
                    proofs.current(user, version, sid).isPresent()));
  }

  @Override
  public Map<String, String> resume(String ticket, UUID user, long version, UUID sid) {
    var pending = matching(user, version, sid).orElseThrow(Unavailable::new);
    if (!pending.ticket().equals(ticket)
        || proofs.current(user, version, sid).isEmpty()
        || !store.remove(ticket)) {
      throw new Unavailable();
    }
    return pending.parameters();
  }

  private Optional<Pending> matching(UUID user, long version, UUID sid) {
    return store
        .load()
        .filter(
            pending ->
                user != null
                    && sid != null
                    && pending.userId().equals(user)
                    && pending.version() == version
                    && pending.sid().equals(sid)
                    && clock.instant().isBefore(pending.expiresAt())
                    && identities.isCurrent(user, version));
  }

  @Override
  public void clear() {
    store.clear();
  }
}

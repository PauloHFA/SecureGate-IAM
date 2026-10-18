package com.securegate.iam.identity.application;

import com.securegate.events.SecurityEventType;
import com.securegate.iam.audit.application.port.AuditRecord;
import com.securegate.iam.audit.application.port.AuditTrail;
import com.securegate.iam.identity.application.port.MfaProofs;
import com.securegate.iam.identity.application.port.MfaSecretProtector;
import com.securegate.iam.identity.application.port.MfaStore;
import com.securegate.iam.identity.application.port.MfaStore.Factor;
import com.securegate.iam.identity.domain.Totp;
import com.securegate.iam.identity.domain.TotpSecret;
import com.securegate.iam.shared.application.port.OperationContext;
import com.securegate.iam.shared.application.port.UnitOfWork;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class MfaService {
  private static final SecureRandom RANDOM = new SecureRandom();
  private final MfaStore store;
  private final MfaProofs proofs;
  private final MfaSecretProtector secrets;
  private final AuditTrail audit;
  private final UnitOfWork transactions;
  private final Clock clock;

  public MfaService(
      MfaStore store,
      MfaProofs proofs,
      MfaSecretProtector secrets,
      AuditTrail audit,
      UnitOfWork transactions,
      Clock clock) {
    this.store = store;
    this.proofs = proofs;
    this.secrets = secrets;
    this.audit = audit;
    this.transactions = transactions;
    this.clock = clock;
  }

  public Status status(Session session) {
    return transactions.execute(
        () -> {
          current(session);
          Factor factor = store.load(session.userId());
          var proof = proofs.current(session.userId(), session.version(), session.sid());
          return new Status(
              factor.state(),
              store.remainingRecovery(factor.id()),
              proof.map(MfaProofs.Proof::expiresAt).orElse(null),
              blocked(factor) ? factor.blockedUntil() : null);
        });
  }

  public Result enroll(Session session, String password, OperationContext context) {
    return transactions.execute(
        () -> {
          current(session);
          Factor previous = store.load(session.userId());
          if (blocked(previous)) {
            return Result.failure("LOCKED");
          }
          Instant now = clock.instant();
          if ("ACTIVE".equals(previous.state())) {
            return Result.failure("DENIED");
          }
          if (session.authenticatedAt() == null
              || session.authenticatedAt().isAfter(now.plusSeconds(5))
              || !session.authenticatedAt().isAfter(now.minusSeconds(300))
              || password == null
              || password.length() > 128
              || !store.passwordMatches(session.userId(), password)) {
            return fail(session, previous, context);
          }
          UUID factorId = UUID.randomUUID();
          TotpSecret secret = TotpSecret.generate();
          String ciphertext = secrets.protect(session.userId(), factorId, secret);
          store.save(
              session.userId(),
              new Factor(
                  factorId,
                  "PENDING",
                  ciphertext,
                  session.sid(),
                  session.version(),
                  now.plusSeconds(300),
                  -1,
                  previous.failures(),
                  previous.blockedUntil()));
          record("identity.mfa.enrollmentstarted", session, "TOTP", context);
          return new Result("SUCCESS", secret.encoded(), List.of());
        });
  }

  public Result confirm(Session session, String code, OperationContext context) {
    return transactions.execute(
        () -> {
          current(session);
          Factor factor = store.load(session.userId());
          if (blocked(factor)) {
            return Result.failure("LOCKED");
          }
          if (!"PENDING".equals(factor.state())
              || !session.sid().equals(factor.enrollmentSession())
              || !Long.valueOf(session.version()).equals(factor.enrollmentVersion())
              || !clock.instant().isBefore(factor.pendingExpiresAt())) {
            return fail(session, factor, context);
          }
          var counter =
              Totp.match(
                  secrets.unprotect(session.userId(), factor.id(), factor.ciphertext()),
                  code,
                  clock.instant(),
                  factor.lastCounter());
          if (counter.isEmpty()) {
            return fail(session, factor, context);
          }
          store.save(
              session.userId(),
              new Factor(
                  factor.id(),
                  "ACTIVE",
                  factor.ciphertext(),
                  null,
                  null,
                  null,
                  counter.getAsLong(),
                  0,
                  null));
          var codes = new ArrayList<String>();
          for (int i = 0; i < 10; i++) {
            byte[] random = new byte[16];
            RANDOM.nextBytes(random);
            codes.add(Base64.getUrlEncoder().withoutPadding().encodeToString(random));
          }
          store.recovery(factor.id(), codes.stream().map(MfaService::hash).toList());
          store.proof(
              session.userId(),
              session.version(),
              session.sid(),
              factor.id(),
              "TOTP",
              clock.instant());
          record("identity.mfa.activated", session, "TOTP", context);
          return new Result("SUCCESS", null, codes);
        });
  }

  public Result verify(Session session, String method, String code, OperationContext context) {
    return transactions.execute(
        () -> {
          current(session);
          Factor factor = store.load(session.userId());
          if (blocked(factor)) {
            return Result.failure("LOCKED");
          }
          if (!"ACTIVE".equals(factor.state())) {
            return fail(session, factor, context);
          }
          long counter = factor.lastCounter();
          if ("TOTP".equals(method)) {
            var matched =
                Totp.match(
                    secrets.unprotect(session.userId(), factor.id(), factor.ciphertext()),
                    code,
                    clock.instant(),
                    counter);
            if (matched.isEmpty()) {
              return fail(session, factor, context);
            }
            counter = matched.getAsLong();
          } else if (!"RECOVERY".equals(method)
              || code == null
              || !code.matches("[A-Za-z0-9_-]{22}")
              || !store.consumeRecovery(factor.id(), hash(code))) {
            return fail(session, factor, context);
          }
          store.save(
              session.userId(),
              new Factor(
                  factor.id(), "ACTIVE", factor.ciphertext(), null, null, null, counter, 0, null));
          store.proof(
              session.userId(),
              session.version(),
              session.sid(),
              factor.id(),
              method,
              clock.instant());
          record(
              "RECOVERY".equals(method) ? "identity.mfa.recoveryused" : "identity.mfa.verified",
              session,
              method,
              context);
          return new Result("SUCCESS", null, List.of());
        });
  }

  private Result fail(Session session, Factor factor, OperationContext context) {
    int count = factor.blockedUntil() != null && !blocked(factor) ? 1 : factor.failures() + 1;
    store.save(
        session.userId(),
        factor.attempts(count, count >= 5 ? clock.instant().plusSeconds(300) : null));
    record("identity.mfa.failed", session, "UNVERIFIED", context);
    return Result.failure(count >= 5 ? "LOCKED" : "DENIED");
  }

  private boolean blocked(Factor factor) {
    return factor.blockedUntil() != null && clock.instant().isBefore(factor.blockedUntil());
  }

  private void current(Session session) {
    if (session == null
        || session.userId() == null
        || session.sid() == null
        || session.version() < 0
        || !store.lockCurrentUser(session.userId(), session.version())
        || !store.lockOpenSession(session.sid())) {
      throw new StaleSession();
    }
  }

  private void record(String event, Session session, String method, OperationContext context) {
    audit.record(
        new AuditRecord(
            new SecurityEventType(event),
            "identity/mfa/" + session.userId(),
            context,
            Map.of("method", method)));
  }

  private static String hash(String code) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(code.getBytes(StandardCharsets.US_ASCII)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 unavailable.");
    }
  }

  public record Session(UUID userId, long version, UUID sid, Instant authenticatedAt) {}

  public record Status(
      String state, int recoveryRemaining, Instant proofExpiresAt, Instant blockedUntil) {}

  public record Result(String outcome, String secret, List<String> recoveryCodes) {
    public Result {
      recoveryCodes = List.copyOf(recoveryCodes);
    }

    static Result failure(String outcome) {
      return new Result(outcome, null, List.of());
    }

    @Override
    public String toString() {
      return "MfaResult[redacted]";
    }
  }

  public static final class StaleSession extends RuntimeException {
    public StaleSession() {
      super("Current browser identity required.");
    }
  }
}

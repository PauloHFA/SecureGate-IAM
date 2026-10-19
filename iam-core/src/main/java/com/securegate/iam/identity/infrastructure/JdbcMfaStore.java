package com.securegate.iam.identity.infrastructure;

import com.securegate.iam.identity.application.port.MfaProofs;
import com.securegate.iam.identity.application.port.MfaStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

public final class JdbcMfaStore implements MfaStore, MfaProofs {
  private final JdbcTemplate jdbc;
  private final Clock clock;
  private final Argon2PasswordEncoder passwords = new Argon2PasswordEncoder(16, 32, 1, 19456, 2);

  public JdbcMfaStore(JdbcTemplate jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  @Override
  public boolean lockCurrentUser(UUID userId, long version) {
    return !jdbc.query(
            "SELECT id FROM iam_identity.users WHERE id=? AND version=? AND status='ACTIVE' FOR UPDATE",
            (rs, row) -> rs.getObject(1, UUID.class),
            userId,
            version)
        .isEmpty();
  }

  @Override
  public boolean lockOpenSession(UUID sessionId) {
    jdbc.update(
        "INSERT INTO iam_session.mfa_session_state(session_id,closed) VALUES (?,false) ON CONFLICT DO NOTHING",
        sessionId);
    return Boolean.FALSE.equals(
        jdbc.queryForObject(
            "SELECT closed FROM iam_session.mfa_session_state WHERE session_id=? FOR UPDATE",
            Boolean.class,
            sessionId));
  }

  @Override
  public boolean passwordMatches(UUID userId, String password) {
    return jdbc
        .query(
            "SELECT password_hash FROM iam_identity.credentials WHERE user_id=?",
            (rs, row) -> passwords.matches(password, rs.getString(1)),
            userId)
        .stream()
        .findFirst()
        .orElse(false);
  }

  @Override
  public Factor load(UUID userId) {
    return jdbc
        .query(
            "SELECT * FROM iam_identity.mfa_factors WHERE user_id=?",
            (rs, row) ->
                new Factor(
                    rs.getObject("id", UUID.class),
                    rs.getString("state"),
                    rs.getString("ciphertext"),
                    rs.getObject("enrollment_session", UUID.class),
                    rs.getObject("enrollment_version", Long.class),
                    instant(rs, "pending_expires_at"),
                    rs.getLong("last_counter"),
                    rs.getInt("failures"),
                    instant(rs, "blocked_until")),
            userId)
        .stream()
        .findFirst()
        .orElseGet(
            () -> new Factor(UUID.randomUUID(), "NONE", null, null, null, null, -1, 0, null));
  }

  @Override
  public void save(UUID userId, Factor factor) {
    jdbc.update(
        """
        INSERT INTO iam_identity.mfa_factors(user_id,id,state,ciphertext,enrollment_session,
          enrollment_version,pending_expires_at,last_counter,failures,blocked_until)
        VALUES (?,?,?,?,?,?,?,?,?,?)
        ON CONFLICT(user_id) DO UPDATE SET id=excluded.id,state=excluded.state,ciphertext=excluded.ciphertext,
          enrollment_session=excluded.enrollment_session,enrollment_version=excluded.enrollment_version,
          pending_expires_at=excluded.pending_expires_at,last_counter=excluded.last_counter,
          failures=excluded.failures,blocked_until=excluded.blocked_until
        """,
        userId,
        factor.id(),
        factor.state(),
        factor.ciphertext(),
        factor.enrollmentSession(),
        factor.enrollmentVersion(),
        timestamp(factor.pendingExpiresAt()),
        factor.lastCounter(),
        factor.failures(),
        timestamp(factor.blockedUntil()));
  }

  @Override
  public void recovery(UUID factorId, List<String> hashes) {
    jdbc.update("DELETE FROM iam_identity.mfa_recovery_codes WHERE factor_id=?", factorId);
    for (String hash : hashes) {
      jdbc.update(
          "INSERT INTO iam_identity.mfa_recovery_codes(factor_id,code_hash) VALUES (?,?)",
          factorId,
          hash);
    }
  }

  @Override
  public boolean consumeRecovery(UUID factorId, String hash) {
    return jdbc.update(
            "DELETE FROM iam_identity.mfa_recovery_codes WHERE factor_id=? AND code_hash=?",
            factorId,
            hash)
        == 1;
  }

  @Override
  public int remainingRecovery(UUID factorId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM iam_identity.mfa_recovery_codes WHERE factor_id=?",
        Integer.class,
        factorId);
  }

  @Override
  public void proof(
      UUID userId, long version, UUID sessionId, UUID factorId, String method, Instant now) {
    jdbc.update(
        """
        INSERT INTO iam_session.mfa_proofs(session_id,user_id,identity_version,factor_id,method,verified_at,expires_at)
        VALUES (?,?,?,?,?,?,?) ON CONFLICT(session_id) DO UPDATE SET
          user_id=excluded.user_id,identity_version=excluded.identity_version,factor_id=excluded.factor_id,
          method=excluded.method,verified_at=excluded.verified_at,expires_at=excluded.expires_at
        """,
        sessionId,
        userId,
        version,
        factorId,
        method,
        timestamp(now),
        timestamp(now.plusSeconds(300)));
  }

  @Override
  public Optional<Proof> current(UUID userId, long identityVersion, UUID sessionId) {
    Instant now = clock.instant();
    return jdbc
        .query(
            """
        SELECT p.method,p.verified_at,p.expires_at FROM iam_session.mfa_proofs p
        JOIN iam_session.mfa_session_state s ON s.session_id=p.session_id AND s.closed=false
        JOIN iam_identity.users u ON u.id=p.user_id
        JOIN iam_identity.mfa_factors f ON f.user_id=u.id AND f.id=p.factor_id
        WHERE p.user_id=? AND p.session_id=? AND p.identity_version=? AND u.version=p.identity_version
          AND u.status='ACTIVE' AND f.state='ACTIVE' AND p.expires_at>? AND p.verified_at<=?
        """,
            (rs, row) ->
                new Proof(
                    rs.getString("method"), instant(rs, "verified_at"), instant(rs, "expires_at")),
            userId,
            sessionId,
            identityVersion,
            timestamp(now),
            timestamp(now))
        .stream()
        .findFirst();
  }

  @Override
  public void revokeSession(UUID sessionId) {
    jdbc.update(
        "INSERT INTO iam_session.mfa_session_state(session_id,closed) VALUES (?,true) ON CONFLICT(session_id) DO UPDATE SET closed=true",
        sessionId);
    jdbc.update("DELETE FROM iam_session.mfa_proofs WHERE session_id=?", sessionId);
  }

  private static Timestamp timestamp(Instant value) {
    return value == null ? null : Timestamp.from(value);
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }
}

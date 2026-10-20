package com.securegate.iam.authorization.infrastructure;

import com.securegate.iam.authorization.application.port.PrivilegedRequests;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcPrivilegedRequests implements PrivilegedRequests {
  private final JdbcTemplate jdbc;

  public JdbcPrivilegedRequests(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insert(Entry entry) {
    jdbc.update(
        "INSERT INTO iam_authorization.privileged_requests(id,user_id,target,justification,created_at,expires_at,origin_correlation_id) VALUES (?,?,?,?,?,?,?)",
        entry.id(),
        entry.userId(),
        entry.target(),
        entry.justification(),
        Timestamp.from(entry.createdAt()),
        Timestamp.from(entry.expiresAt()),
        entry.originCorrelationId());
  }

  @Override
  public Optional<Entry> find(UUID id, UUID userId) {
    return jdbc
        .query(
            "SELECT * FROM iam_authorization.privileged_requests WHERE id=? AND user_id=?",
            JdbcPrivilegedRequests::row,
            id,
            userId)
        .stream()
        .findFirst();
  }

  private static Entry row(java.sql.ResultSet rows, int n) throws java.sql.SQLException {
    return new Entry(
        rows.getObject("id", UUID.class),
        rows.getObject("user_id", UUID.class),
        rows.getString("target"),
        rows.getString("justification"),
        rows.getTimestamp("created_at").toInstant(),
        rows.getTimestamp("expires_at").toInstant(),
        rows.getTimestamp("revoked_at") == null
            ? null
            : rows.getTimestamp("revoked_at").toInstant(),
        rows.getString("origin_correlation_id") == null
            ? rows.getObject("id", UUID.class).toString()
            : rows.getString("origin_correlation_id"),
        rows.getInt("observation_attempts"));
  }

  @Override
  public boolean revoke(UUID id, UUID userId, Instant now) {
    return jdbc.update(
            "UPDATE iam_authorization.privileged_requests SET revoked_at=? WHERE id=? AND user_id=? AND revoked_at IS NULL AND expires_at>?",
            Timestamp.from(now),
            id,
            userId,
            Timestamp.from(now))
        == 1;
  }

  @Override
  public Optional<Entry> lock(UUID id, UUID userId) {
    jdbc.queryForList(
        "SELECT id FROM iam_authorization.privileged_requests WHERE id=? AND user_id=? FOR UPDATE",
        id,
        userId);
    return find(id, userId);
  }

  @Override
  public Optional<Entry> forAdministration(UUID id) {
    var owners =
        jdbc.queryForList(
            "SELECT user_id FROM iam_authorization.privileged_requests WHERE id=?", UUID.class, id);
    return owners.isEmpty() ? Optional.empty() : find(id, owners.getFirst());
  }

  @Override
  public List<Entry> expired(Instant now) {
    return jdbc.query(
        "SELECT * FROM iam_authorization.privileged_requests WHERE expires_at<=? AND next_observation_at<=? AND revoked_at IS NULL AND expiration_recorded_at IS NULL ORDER BY GREATEST(next_observation_at,expires_at),id LIMIT 20",
        JdbcPrivilegedRequests::row,
        Timestamp.from(now),
        Timestamp.from(now));
  }

  @Override
  public boolean recordExpiration(UUID id, Instant now) {
    return jdbc.update(
            "UPDATE iam_authorization.privileged_requests SET expiration_recorded_at=? WHERE id=? AND expires_at<=? AND revoked_at IS NULL AND expiration_recorded_at IS NULL",
            Timestamp.from(now),
            id,
            Timestamp.from(now))
        == 1;
  }

  @Override
  public boolean deferObservation(
      UUID id, int expectedAttempts, Instant failedAt, Instant retryAt) {
    return jdbc.update(
            """
        UPDATE iam_authorization.privileged_requests SET
          observation_attempts=LEAST(30,observation_attempts+1),
          next_observation_at=GREATEST(next_observation_at,?),
          last_observation_failure_at=?,last_observation_failure_code='OBSERVATION_FAILED'
        WHERE id=? AND observation_attempts=? AND expires_at<=? AND revoked_at IS NULL
          AND expiration_recorded_at IS NULL
        """,
            Timestamp.from(retryAt),
            Timestamp.from(failedAt),
            id,
            expectedAttempts,
            Timestamp.from(failedAt))
        == 1;
  }

  @Override
  public Observation operations(Instant now) {
    return jdbc.queryForObject(
        """
        SELECT COUNT(*) AS pending,
          COUNT(*) FILTER (WHERE next_observation_at<=?) AS ready,
          COUNT(*) FILTER (WHERE next_observation_at>?) AS deferred,
          COUNT(*) FILTER (WHERE observation_attempts>0) AS retrying,
          GREATEST(0,COALESCE(EXTRACT(EPOCH FROM (? - MIN(expires_at))),0)) AS oldest
        FROM iam_authorization.privileged_requests
        WHERE expires_at<=? AND revoked_at IS NULL AND expiration_recorded_at IS NULL
        """,
        (rows, index) ->
            new Observation(
                rows.getLong("pending"),
                rows.getLong("ready"),
                rows.getLong("deferred"),
                rows.getLong("retrying"),
                rows.getDouble("oldest")),
        Timestamp.from(now),
        Timestamp.from(now),
        Timestamp.from(now),
        Timestamp.from(now));
  }
}

package com.securegate.iam.authorization.infrastructure;

import com.securegate.iam.authorization.application.port.PrivilegedIssuances;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcPrivilegedIssuances implements PrivilegedIssuances {
  private final JdbcTemplate jdbc;

  public JdbcPrivilegedIssuances(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public boolean start(UUID id, Instant now, String correlationId) {
    return jdbc.update(
            "INSERT INTO iam_authorization.privileged_issuances(request_id,state,created_at,origin_correlation_id) VALUES (?,'ISSUING',?,?) ON CONFLICT DO NOTHING",
            id,
            Timestamp.from(now),
            correlationId)
        == 1;
  }

  @Override
  public boolean finish(UUID id, String lease, Instant expiry) {
    return jdbc.update(
            "UPDATE iam_authorization.privileged_issuances SET state='ISSUED',lease_id=?,expires_at=? WHERE request_id=? AND state='ISSUING'",
            lease,
            Timestamp.from(expiry),
            id)
        == 1;
  }

  @Override
  public boolean abort(UUID id, String lease, Instant expiry) {
    return jdbc.update(
            "UPDATE iam_authorization.privileged_issuances SET state=?,lease_id=?,expires_at=? WHERE request_id=? AND state='ISSUING'",
            lease == null ? "FAILED" : "REVOKING",
            lease,
            expiry == null ? null : Timestamp.from(expiry),
            id)
        == 1;
  }

  private static Entry row(java.sql.ResultSet rows, int index) throws java.sql.SQLException {
    return new Entry(
        rows.getObject("request_id", UUID.class),
        rows.getString("state"),
        rows.getString("lease_id"),
        rows.getTimestamp("expires_at") == null
            ? null
            : rows.getTimestamp("expires_at").toInstant(),
        rows.getString("origin_correlation_id") == null
            ? rows.getObject("request_id", UUID.class).toString()
            : rows.getString("origin_correlation_id"),
        rows.getInt("cleanup_attempts"));
  }

  @Override
  public Optional<Entry> find(UUID id) {
    return jdbc
        .query(
            "SELECT * FROM iam_authorization.privileged_issuances WHERE request_id=?",
            JdbcPrivilegedIssuances::row,
            id)
        .stream()
        .findFirst();
  }

  @Override
  public List<Entry> pending(Instant now) {
    return jdbc.query(
        "SELECT i.* FROM iam_authorization.privileged_issuances i JOIN iam_authorization.privileged_requests r ON r.id=i.request_id WHERE i.next_cleanup_at<=? AND (i.state='REVOKING' OR (i.state='ISSUED' AND (r.revoked_at IS NOT NULL OR i.expires_at<=?)) OR (i.state='ISSUING' AND i.created_at<=?)) ORDER BY GREATEST(i.next_cleanup_at,i.created_at),i.request_id LIMIT 10",
        JdbcPrivilegedIssuances::row,
        Timestamp.from(now),
        Timestamp.from(now),
        Timestamp.from(now.minusSeconds(30)));
  }

  @Override
  public boolean revoked(UUID id) {
    return jdbc.update(
            "UPDATE iam_authorization.privileged_issuances SET state='REVOKED' WHERE request_id=? AND state IN ('ISSUED','REVOKING')",
            id)
        == 1;
  }

  @Override
  public boolean revoking(UUID id) {
    return jdbc.update(
            "UPDATE iam_authorization.privileged_issuances SET state='REVOKING' WHERE request_id=? AND state='ISSUED'",
            id)
        == 1;
  }

  @Override
  public boolean abandoned(UUID id, Instant cutoff) {
    return jdbc.update(
            "UPDATE iam_authorization.privileged_issuances SET state='FAILED' WHERE request_id=? AND state='ISSUING' AND created_at<=?",
            id,
            Timestamp.from(cutoff))
        == 1;
  }

  @Override
  public boolean recordExpiration(UUID id, Instant now) {
    return jdbc.update(
            "UPDATE iam_authorization.privileged_issuances SET expiration_recorded_at=? WHERE request_id=? AND expires_at<=? AND state IN ('ISSUED','REVOKING') AND expiration_recorded_at IS NULL",
            Timestamp.from(now),
            id,
            Timestamp.from(now))
        == 1;
  }

  @Override
  public boolean defer(UUID id, int expectedAttempts, Instant failedAt, Instant retryAt) {
    return jdbc.update(
            "UPDATE iam_authorization.privileged_issuances SET cleanup_attempts=LEAST(30,cleanup_attempts+1),next_cleanup_at=GREATEST(next_cleanup_at,?),last_cleanup_failure_at=?,last_cleanup_failure_code='CLEANUP_FAILED' WHERE request_id=? AND cleanup_attempts=? AND state IN ('ISSUING','ISSUED','REVOKING')",
            Timestamp.from(retryAt),
            Timestamp.from(failedAt),
            id,
            expectedAttempts)
        == 1;
  }

  @Override
  public Operations operations(Instant now) {
    return jdbc.queryForObject(
        """
        WITH work AS (
          SELECT i.*,
            (i.state='REVOKING' OR
             (i.state='ISSUED' AND (r.revoked_at IS NOT NULL OR i.expires_at<=?)) OR
             (i.state='ISSUING' AND i.created_at<=?)) AS pending
          FROM iam_authorization.privileged_issuances i
          JOIN iam_authorization.privileged_requests r ON r.id=i.request_id
        )
        SELECT COUNT(*) FILTER (WHERE pending) AS pending,
          COUNT(*) FILTER (WHERE pending AND next_cleanup_at<=?) AS ready,
          COUNT(*) FILTER (WHERE pending AND next_cleanup_at>?) AS deferred,
          COUNT(*) FILTER (WHERE pending AND cleanup_attempts>0) AS retrying,
          COUNT(*) FILTER (WHERE state='FAILED' AND lease_id IS NULL) AS ambiguous,
          GREATEST(0,COALESCE(EXTRACT(EPOCH FROM (? - MIN(created_at) FILTER (WHERE pending))),0)) AS oldest
        FROM work
        """,
        (rows, index) ->
            new Operations(
                rows.getLong("pending"),
                rows.getLong("ready"),
                rows.getLong("deferred"),
                rows.getLong("retrying"),
                rows.getLong("ambiguous"),
                rows.getDouble("oldest")),
        Timestamp.from(now),
        Timestamp.from(now.minusSeconds(30)),
        Timestamp.from(now),
        Timestamp.from(now),
        Timestamp.from(now));
  }
}

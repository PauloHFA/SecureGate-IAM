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
            : rows.getString("origin_correlation_id"));
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
        "SELECT * FROM iam_authorization.privileged_requests WHERE expires_at<=? AND revoked_at IS NULL AND expiration_recorded_at IS NULL ORDER BY expires_at,id LIMIT 20",
        JdbcPrivilegedRequests::row,
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
}

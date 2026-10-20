package com.securegate.iam.authorization.infrastructure;

import com.securegate.iam.authorization.application.port.PrivilegedRequests;
import java.sql.Timestamp;
import java.time.Instant;
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
        "INSERT INTO iam_authorization.privileged_requests(id,user_id,target,justification,created_at,expires_at) VALUES (?,?,?,?,?,?)",
        entry.id(),
        entry.userId(),
        entry.target(),
        entry.justification(),
        Timestamp.from(entry.createdAt()),
        Timestamp.from(entry.expiresAt()));
  }

  @Override
  public Optional<Entry> find(UUID id, UUID userId) {
    return jdbc
        .query(
            "SELECT * FROM iam_authorization.privileged_requests WHERE id=? AND user_id=?",
            (rows, n) ->
                new Entry(
                    rows.getObject("id", UUID.class),
                    rows.getObject("user_id", UUID.class),
                    rows.getString("target"),
                    rows.getString("justification"),
                    rows.getTimestamp("created_at").toInstant(),
                    rows.getTimestamp("expires_at").toInstant(),
                    rows.getTimestamp("revoked_at") == null
                        ? null
                        : rows.getTimestamp("revoked_at").toInstant()),
            id,
            userId)
        .stream()
        .findFirst();
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
}

package com.securegate.iam.identity.infrastructure;

import com.securegate.iam.identity.application.port.IdentityProfiles;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcIdentityProfiles implements IdentityProfiles {
  private final JdbcTemplate jdbc;

  public JdbcIdentityProfiles(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<Profile> findCurrent(UUID id, long version) {
    return jdbc
        .query(
            "SELECT display_name,email FROM iam_identity.users WHERE id=? AND version=? AND status='ACTIVE'",
            (rs, row) -> new Profile(rs.getString(1), rs.getString(2)),
            id,
            version)
        .stream()
        .findFirst();
  }
}

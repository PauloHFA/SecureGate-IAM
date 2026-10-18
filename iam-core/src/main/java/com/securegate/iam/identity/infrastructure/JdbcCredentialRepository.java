package com.securegate.iam.identity.infrastructure;

import com.securegate.iam.identity.application.port.CredentialRepository;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public class JdbcCredentialRepository implements CredentialRepository {
  private final JdbcTemplate jdbc;

  public JdbcCredentialRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void create(UUID userId, String hash) {
    jdbc.update(
        "INSERT INTO iam_identity.credentials (user_id,password_hash) VALUES (?,?)", userId, hash);
  }

  @Override
  public void replace(UUID userId, String hash) {
    if (jdbc.update(
            "UPDATE iam_identity.credentials SET password_hash=?,changed_at=current_timestamp WHERE user_id=?",
            hash,
            userId)
        != 1) {
      throw new IllegalStateException("Credential record unavailable");
    }
  }
}

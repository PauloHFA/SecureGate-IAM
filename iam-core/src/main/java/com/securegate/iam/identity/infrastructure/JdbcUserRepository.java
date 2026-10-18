package com.securegate.iam.identity.infrastructure;

import com.securegate.iam.identity.application.port.UserRepository;
import com.securegate.iam.identity.domain.EmailAddress;
import com.securegate.iam.identity.domain.User;
import com.securegate.iam.identity.domain.UserStatus;
import com.securegate.iam.shared.application.ConcurrentUpdateException;
import com.securegate.iam.shared.application.port.PageRequest;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

public class JdbcUserRepository implements UserRepository {
  private static final RowMapper<User> MAPPER =
      (row, index) ->
          new User(
              row.getObject("id", UUID.class),
              new EmailAddress(row.getString("email")),
              row.getString("display_name"),
              UserStatus.valueOf(row.getString("status")),
              row.getLong("version"));
  private final JdbcTemplate jdbc;

  public JdbcUserRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void create(User user) {
    jdbc.update(
        "INSERT INTO iam_identity.users (id,email,display_name,status,version) VALUES (?,?,?,?,?)",
        user.id(),
        user.email().value(),
        user.displayName(),
        user.status().name(),
        user.version());
  }

  @Override
  public Optional<User> find(UUID id) {
    return jdbc
        .query(
            "SELECT id,email,display_name,status,version FROM iam_identity.users WHERE id=?",
            MAPPER,
            id)
        .stream()
        .findFirst();
  }

  @Override
  public List<User> list(PageRequest page) {
    return jdbc.query(
        "SELECT id,email,display_name,status,version FROM iam_identity.users ORDER BY created_at,id LIMIT ? OFFSET ?",
        MAPPER,
        page.limit(),
        page.offset());
  }

  @Override
  public void update(User user, long expectedVersion) {
    int changed =
        jdbc.update(
            "UPDATE iam_identity.users SET email=?,display_name=?,status=?,version=?,updated_at=current_timestamp WHERE id=? AND version=?",
            user.email().value(),
            user.displayName(),
            user.status().name(),
            user.version(),
            user.id(),
            expectedVersion);
    if (changed != 1) {
      throw new ConcurrentUpdateException();
    }
  }
}

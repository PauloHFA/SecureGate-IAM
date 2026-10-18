package com.securegate.iam.access.infrastructure;

import com.securegate.iam.access.application.port.EffectiveRoles;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcEffectiveRoles implements EffectiveRoles {
  private final JdbcTemplate jdbc;

  public JdbcEffectiveRoles(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<String> forUser(UUID id) {
    return jdbc.queryForList(
        """
        SELECT r.name FROM iam_access.roles r
        JOIN iam_access.user_roles ur ON ur.role_id=r.id WHERE ur.user_id=?
        UNION
        SELECT r.name FROM iam_access.roles r
        JOIN iam_access.group_roles gr ON gr.role_id=r.id
        JOIN iam_access.group_members gm ON gm.group_id=gr.group_id WHERE gm.user_id=?
        ORDER BY name
        """,
        String.class,
        id,
        id);
  }
}

package com.securegate.iam.access.infrastructure;

import com.securegate.iam.access.application.Assignment;
import com.securegate.iam.access.application.port.AccessRepository;
import com.securegate.iam.access.domain.Group;
import com.securegate.iam.access.domain.Permission;
import com.securegate.iam.access.domain.Role;
import com.securegate.iam.shared.application.port.PageRequest;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

public class JdbcAccessRepository implements AccessRepository {
  private static final RowMapper<Role> ROLE =
      (row, index) -> new Role(row.getObject("id", UUID.class), row.getString("name"));
  private static final RowMapper<Group> GROUP =
      (row, index) -> new Group(row.getObject("id", UUID.class), row.getString("name"));
  private static final RowMapper<Permission> PERMISSION =
      (row, index) ->
          new Permission(
              row.getObject("id", UUID.class), row.getString("resource"), row.getString("action"));
  private final JdbcTemplate jdbc;

  public JdbcAccessRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void createRole(Role role) {
    jdbc.update("INSERT INTO iam_access.roles (id,name) VALUES (?,?)", role.id(), role.name());
  }

  @Override
  public void createGroup(Group group) {
    jdbc.update("INSERT INTO iam_access.groups (id,name) VALUES (?,?)", group.id(), group.name());
  }

  @Override
  public void createPermission(Permission permission) {
    jdbc.update(
        "INSERT INTO iam_access.permissions (id,resource,action) VALUES (?,?,?)",
        permission.id(),
        permission.resource(),
        permission.action());
  }

  @Override
  public Optional<Role> role(UUID id) {
    return jdbc.query("SELECT id,name FROM iam_access.roles WHERE id=?", ROLE, id).stream()
        .findFirst();
  }

  @Override
  public Optional<Group> group(UUID id) {
    return jdbc.query("SELECT id,name FROM iam_access.groups WHERE id=?", GROUP, id).stream()
        .findFirst();
  }

  @Override
  public Optional<Permission> permission(UUID id) {
    return jdbc
        .query("SELECT id,resource,action FROM iam_access.permissions WHERE id=?", PERMISSION, id)
        .stream()
        .findFirst();
  }

  @Override
  public List<Role> roles(PageRequest page) {
    return jdbc.query(
        "SELECT id,name FROM iam_access.roles ORDER BY name,id LIMIT ? OFFSET ?",
        ROLE,
        page.limit(),
        page.offset());
  }

  @Override
  public List<Group> groups(PageRequest page) {
    return jdbc.query(
        "SELECT id,name FROM iam_access.groups ORDER BY name,id LIMIT ? OFFSET ?",
        GROUP,
        page.limit(),
        page.offset());
  }

  @Override
  public List<Permission> permissions(PageRequest page) {
    return jdbc.query(
        "SELECT id,resource,action FROM iam_access.permissions ORDER BY resource,action,id LIMIT ? OFFSET ?",
        PERMISSION,
        page.limit(),
        page.offset());
  }

  @Override
  public boolean assign(Assignment assignment, UUID left, UUID right, boolean grant) {
    String table;
    String leftColumn;
    String rightColumn;
    switch (assignment) {
      case USER_ROLE -> {
        table = "user_roles";
        leftColumn = "user_id";
        rightColumn = "role_id";
      }
      case GROUP_MEMBER -> {
        table = "group_members";
        leftColumn = "group_id";
        rightColumn = "user_id";
      }
      case GROUP_ROLE -> {
        table = "group_roles";
        leftColumn = "group_id";
        rightColumn = "role_id";
      }
      case ROLE_PERMISSION -> {
        table = "role_permissions";
        leftColumn = "role_id";
        rightColumn = "permission_id";
      }
      default -> throw new IllegalArgumentException("Unknown assignment");
    }
    // Identifiers come exclusively from the enum above; all input values remain bound parameters.
    String sql =
        grant
            ? "INSERT INTO iam_access."
                + table
                + " ("
                + leftColumn
                + ","
                + rightColumn
                + ") VALUES (?,?) ON CONFLICT DO NOTHING"
            : "DELETE FROM iam_access."
                + table
                + " WHERE "
                + leftColumn
                + "=? AND "
                + rightColumn
                + "=?";
    return jdbc.update(sql, left, right) != 0;
  }

  @Override
  public Set<String> effectivePermissions(UUID userId) {
    return new HashSet<>(
        jdbc.queryForList(
            """
        SELECT DISTINCT p.resource || ':' || p.action
        FROM iam_access.permissions p
        JOIN iam_access.role_permissions rp ON rp.permission_id = p.id
        WHERE rp.role_id IN (
          SELECT role_id FROM iam_access.user_roles WHERE user_id = ?
          UNION
          SELECT gr.role_id FROM iam_access.group_roles gr
          JOIN iam_access.group_members gm ON gm.group_id = gr.group_id WHERE gm.user_id = ?
        )
        ORDER BY 1
        """,
            String.class,
            userId,
            userId));
  }
}

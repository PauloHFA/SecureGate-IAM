package com.securegate.iam.identity.infrastructure;

import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

public final class JdbcLoginUsers implements UserDetailsService {
  private final JdbcTemplate jdbc;

  public JdbcLoginUsers(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public UserDetails loadUserByUsername(String email) {
    if (email == null || email.length() > 254) {
      throw new UsernameNotFoundException("Bad credentials");
    }
    return jdbc
        .query(
            """
        SELECT u.id, u.version, u.status, c.password_hash
        FROM iam_identity.users u JOIN iam_identity.credentials c ON c.user_id=u.id
        WHERE u.email=?
        """,
            (rs, row) ->
                LoginPrincipal.forLogin(
                    rs.getString("id"),
                    rs.getLong("version"),
                    "ACTIVE".equals(rs.getString("status")),
                    rs.getString("password_hash")),
            email.strip().toLowerCase(Locale.ROOT))
        .stream()
        .findFirst()
        .orElseThrow(() -> new UsernameNotFoundException("Bad credentials"));
  }
}

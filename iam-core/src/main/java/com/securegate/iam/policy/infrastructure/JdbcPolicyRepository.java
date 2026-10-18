package com.securegate.iam.policy.infrastructure;

import com.securegate.iam.policy.application.port.PolicyDocuments;
import com.securegate.iam.policy.application.port.PolicyRepository;
import com.securegate.iam.policy.domain.Policy;
import com.securegate.iam.shared.application.ConcurrentUpdateException;
import com.securegate.iam.shared.application.port.PageRequest;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

public final class JdbcPolicyRepository implements PolicyRepository {
  private static final String COLUMNS = "id,name,priority,enabled,version,document";
  private final JdbcTemplate jdbc;
  private final PolicyDocuments documents;
  private final RowMapper<Policy> row;

  public JdbcPolicyRepository(JdbcTemplate jdbc, PolicyDocuments documents) {
    this.jdbc = jdbc;
    this.documents = documents;
    this.row =
        (rs, index) -> {
          try {
            return new Policy(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getInt("priority"),
                rs.getBoolean("enabled"),
                rs.getLong("version"),
                documents.decode(rs.getString("document")));
          } catch (IllegalArgumentException failure) {
            throw new IllegalStateException("Stored policy is invalid");
          }
        };
  }

  @Override
  public void create(Policy policy) {
    jdbc.update(
        "INSERT INTO iam_policy.policies(id,name,priority,enabled,version,document) VALUES (?,?,?,?,?,?::jsonb)",
        policy.id(),
        policy.name(),
        policy.priority(),
        policy.enabled(),
        policy.version(),
        documents.encode(policy.document()));
  }

  @Override
  public void update(Policy policy, long expectedVersion) {
    int changed =
        jdbc.update(
            "UPDATE iam_policy.policies SET name=?,priority=?,enabled=?,version=?,document=?::jsonb,updated_at=current_timestamp WHERE id=? AND version=?",
            policy.name(),
            policy.priority(),
            policy.enabled(),
            policy.version(),
            documents.encode(policy.document()),
            policy.id(),
            expectedVersion);
    if (changed != 1) {
      throw new ConcurrentUpdateException();
    }
  }

  @Override
  public Optional<Policy> find(UUID id) {
    return jdbc
        .query("SELECT " + COLUMNS + " FROM iam_policy.policies WHERE id=?", row, id)
        .stream()
        .findFirst();
  }

  @Override
  public List<Policy> list(PageRequest page) {
    return jdbc.query(
        "SELECT "
            + COLUMNS
            + " FROM iam_policy.policies ORDER BY priority DESC,id LIMIT ? OFFSET ?",
        row,
        page.limit(),
        page.offset());
  }

  @Override
  public List<Policy> candidates(String resource, String action, int limit) {
    return jdbc.query(
        "SELECT "
            + COLUMNS
            + " FROM iam_policy.policies WHERE enabled AND document->>'resource' IN (?, '*') AND document->>'action' IN (?, '*') ORDER BY priority DESC,id LIMIT ?",
        row,
        resource,
        action,
        limit);
  }
}

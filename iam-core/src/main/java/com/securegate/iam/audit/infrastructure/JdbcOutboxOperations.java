package com.securegate.iam.audit.infrastructure;

import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Delivery metadata only; audit records and unpublished envelopes are never purged. */
public final class JdbcOutboxOperations {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;

  public JdbcOutboxOperations(JdbcTemplate jdbc, PlatformTransactionManager manager) {
    this.jdbc = jdbc;
    transactions = new TransactionTemplate(manager);
    transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    transactions.setTimeout(5);
  }

  public Map<String, Object> snapshot() {
    return transactions.execute(
        status ->
            jdbc.queryForMap(
                """
        SELECT count(*) FILTER (WHERE published_at IS NULL) AS pending,
          count(*) FILTER (WHERE published_at IS NOT NULL) AS published,
          count(*) FILTER (WHERE quarantined_at IS NOT NULL) AS quarantined,
          count(*) FILTER (WHERE published_at IS NULL AND quarantined_at IS NULL
            AND next_attempt_at<=statement_timestamp()
            AND (lease_until IS NULL OR lease_until<=statement_timestamp())) AS due,
          count(*) FILTER (WHERE published_at IS NULL AND lease_until>statement_timestamp()) AS leased,
          count(*) FILTER (WHERE published_at IS NULL AND lease_until<=statement_timestamp()) AS expired,
          count(*) FILTER (WHERE published_at IS NULL AND last_error_code IS NOT NULL) AS failed,
          COALESCE(GREATEST(0,EXTRACT(EPOCH FROM (statement_timestamp()-
            min(created_at) FILTER (WHERE published_at IS NULL)))),0) AS oldest_seconds
        FROM iam_audit.outbox_events
        """));
  }

  public int purgePublished(int retentionDays, int limit) {
    if (retentionDays < 1 || retentionDays > 3650 || limit < 1 || limit > 1000) {
      throw new IllegalArgumentException("Invalid outbox retention bounds.");
    }
    return transactions.execute(
        status ->
            jdbc.update(
                """
        WITH candidates AS (
          SELECT event_id FROM iam_audit.outbox_events
          WHERE published_at < statement_timestamp()-(? * interval '1 day')
          ORDER BY published_at,event_id FOR UPDATE SKIP LOCKED LIMIT ?
        )
        DELETE FROM iam_audit.outbox_events o USING candidates c WHERE o.event_id=c.event_id
        """,
                retentionDays,
                limit));
  }
}

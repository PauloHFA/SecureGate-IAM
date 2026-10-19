package com.securegate.iam.audit.infrastructure;

import com.securegate.iam.audit.application.port.EventTransport;
import com.securegate.iam.audit.application.port.OutboxDelivery;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

public final class JdbcOutboxDelivery implements OutboxDelivery {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;

  public JdbcOutboxDelivery(JdbcTemplate jdbc, PlatformTransactionManager manager) {
    this.jdbc = jdbc;
    transactions = new TransactionTemplate(manager);
    transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  @Override
  public Optional<Claim> claim(int leaseSeconds) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("Publisher must run outside a business transaction.");
    }
    if (leaseSeconds < 10 || leaseSeconds > 300) {
      throw new IllegalArgumentException("Invalid lease.");
    }
    return transactions.execute(
        status ->
            jdbc
                .query(
                    """
        WITH candidate AS (
          SELECT event_id FROM iam_audit.outbox_events
          WHERE published_at IS NULL AND next_attempt_at<=statement_timestamp()
            AND (lease_until IS NULL OR lease_until<=statement_timestamp())
          ORDER BY next_attempt_at,created_at,event_id
          FOR UPDATE SKIP LOCKED LIMIT 1
        )
        UPDATE iam_audit.outbox_events o
        SET claim_id=?,lease_until=clock_timestamp()+(? * interval '1 second'),
            attempts=LEAST(attempts,999999)+1
        FROM candidate c WHERE o.event_id=c.event_id
        RETURNING o.event_id,o.event_type,o.schema_version,o.payload::text,o.attempts,o.claim_id
        """,
                    (rs, row) ->
                        new Claim(
                            rs.getObject(1, UUID.class),
                            rs.getString(2),
                            rs.getInt(3),
                            rs.getString(4),
                            rs.getInt(5),
                            rs.getObject(6, UUID.class)),
                    UUID.randomUUID(),
                    leaseSeconds)
                .stream()
                .findFirst());
  }

  @Override
  public boolean published(Claim claim) {
    return Boolean.TRUE.equals(
        transactions.execute(
            status ->
                jdbc.update(
                        """
        UPDATE iam_audit.outbox_events SET published_at=clock_timestamp(),claim_id=NULL,
          lease_until=NULL,last_error_code=NULL
        WHERE event_id=? AND claim_id=? AND lease_until>clock_timestamp() AND published_at IS NULL
        """,
                        claim.eventId(),
                        claim.claimId())
                    == 1));
  }

  @Override
  public boolean retry(Claim claim, EventTransport.Outcome failure, int delaySeconds) {
    if (failure == null
        || failure == EventTransport.Outcome.ACKED
        || delaySeconds < 1
        || delaySeconds > 300) {
      throw new IllegalArgumentException("Invalid retry.");
    }
    return Boolean.TRUE.equals(
        transactions.execute(
            status ->
                jdbc.update(
                        """
        UPDATE iam_audit.outbox_events SET claim_id=NULL,lease_until=NULL,last_error_code=?,
          next_attempt_at=clock_timestamp()+(? * interval '1 second')
        WHERE event_id=? AND claim_id=? AND lease_until>clock_timestamp() AND published_at IS NULL
        """,
                        failure.name(),
                        delaySeconds,
                        claim.eventId(),
                        claim.claimId())
                    == 1));
  }
}

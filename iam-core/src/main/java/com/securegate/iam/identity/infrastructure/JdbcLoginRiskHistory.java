package com.securegate.iam.identity.infrastructure;

import com.securegate.events.SecurityEventType;
import com.securegate.iam.audit.application.port.AuditRecord;
import com.securegate.iam.audit.application.port.AuditTrail;
import com.securegate.iam.identity.application.port.LoginRiskHistory;
import com.securegate.iam.shared.application.port.OperationContext;
import com.securegate.iam.shared.application.port.UnitOfWork;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcLoginRiskHistory implements LoginRiskHistory {
  private final JdbcTemplate jdbc;
  private final Clock clock;
  private final boolean enabled;
  private final AtomicReference<Instant> completeAfter;
  private final AuditTrail audit;
  private final UnitOfWork transactions;
  private final Supplier<OperationContext> context;

  public JdbcLoginRiskHistory(
      JdbcTemplate jdbc,
      Clock clock,
      boolean enabled,
      AuditTrail audit,
      UnitOfWork transactions,
      Supplier<OperationContext> context) {
    this.jdbc = jdbc;
    this.clock = clock;
    this.enabled = enabled;
    this.audit = audit;
    this.transactions = transactions;
    this.context = context;
    completeAfter = new AtomicReference<>(clock.instant().plusSeconds(300));
  }

  @Override
  public void recordFailure(String email) {
    recordFailure(email, null);
  }

  @Override
  public void recordFailure(String email, String remoteAddress) {
    if (!enabled) {
      return;
    }
    try {
      transactions.execute(
          () -> {
            var data = new HashMap<String, String>();
            UUID attemptId = UUID.randomUUID();
            data.put("attemptId", attemptId.toString());
            data.put("reason", "CREDENTIALS_OR_ACCOUNT_REJECTED");
            if (remoteAddress != null) {
              data.put(
                  "remoteAddress",
                  com.securegate.events.SecurityEventData.numericAddress(remoteAddress));
            }
            if (email != null && email.length() <= 254) {
              String normalized = email.strip().toLowerCase(Locale.ROOT);
              jdbc.update(
                  "INSERT INTO iam_identity.login_failures(id,user_id,occurred_at) SELECT ?,id,? FROM iam_identity.users WHERE email=?",
                  attemptId,
                  Timestamp.from(clock.instant()),
                  normalized);
              jdbc.query(
                  "SELECT id,version FROM iam_identity.users WHERE email=?",
                  (rs, row) -> {
                    data.put("userId", rs.getObject(1, UUID.class).toString());
                    data.put("version", Long.toString(rs.getLong(2)));
                    return rs.getObject(1, UUID.class);
                  },
                  normalized);
            }
            audit.record(
                new AuditRecord(
                    new SecurityEventType("auth.login.failed"), "login", context.get(), data));
            return null;
          });
    } catch (RuntimeException failure) {
      completeAfter.accumulateAndGet(
          clock.instant().plusSeconds(300),
          (previous, next) -> previous.isAfter(next) ? previous : next);
      // Preserve the generic login failure, but never claim a complete safe counter afterward.
      LoggerFactory.getLogger(JdbcLoginRiskHistory.class)
          .warn("Login risk history unavailable (type={})", failure.getClass().getSimpleName());
    }
  }

  @Override
  public Integer recentFailures(UUID userId) {
    var now = clock.instant();
    if (!enabled || now.isBefore(completeAfter.get())) {
      return null;
    }
    return jdbc.queryForObject(
        "SELECT LEAST(count(*),1000)::integer FROM iam_identity.login_failures WHERE user_id=? AND occurred_at>=? AND occurred_at<=?",
        Integer.class,
        userId,
        Timestamp.from(now.minusSeconds(300)),
        Timestamp.from(now));
  }
}

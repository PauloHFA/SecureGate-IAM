package com.securegate.iam.identity.infrastructure;

import com.securegate.iam.identity.application.port.LoginRiskHistory;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcLoginRiskHistory implements LoginRiskHistory {
  private final JdbcTemplate jdbc;
  private final Clock clock;
  private final boolean enabled;
  private final AtomicReference<Instant> completeAfter;

  public JdbcLoginRiskHistory(JdbcTemplate jdbc, Clock clock, boolean enabled) {
    this.jdbc = jdbc;
    this.clock = clock;
    this.enabled = enabled;
    completeAfter = new AtomicReference<>(clock.instant().plusSeconds(300));
  }

  @Override
  public void recordFailure(String email) {
    if (!enabled || email == null || email.length() > 254) {
      return;
    }
    try {
      jdbc.update(
          "INSERT INTO iam_identity.login_failures(id,user_id,occurred_at) SELECT ?,id,? FROM iam_identity.users WHERE email=?",
          UUID.randomUUID(),
          Timestamp.from(clock.instant()),
          email.strip().toLowerCase(Locale.ROOT));
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

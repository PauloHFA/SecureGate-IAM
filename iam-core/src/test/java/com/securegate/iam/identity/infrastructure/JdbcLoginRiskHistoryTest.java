package com.securegate.iam.identity.infrastructure;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcLoginRiskHistoryTest {
  @Test
  void warmupAndWriteFailurePreventAFalseZeroAndRecoveryRequiresFullWindow() {
    var now = Instant.parse("2026-10-18T18:00:00Z");
    Clock clock = mock(Clock.class);
    when(clock.instant()).thenReturn(now);
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    var history = new JdbcLoginRiskHistory(jdbc, clock, true);
    UUID user = UUID.randomUUID();
    assertNull(history.recentFailures(user));
    when(clock.instant()).thenReturn(now.plusSeconds(300));
    when(jdbc.queryForObject(
            anyString(), eq(Integer.class), eq(user), any(Timestamp.class), any(Timestamp.class)))
        .thenReturn(2);
    assertEquals(2, history.recentFailures(user));
    doThrow(new IllegalStateException("synthetic"))
        .when(jdbc)
        .update(anyString(), any(UUID.class), any(Timestamp.class), anyString());
    assertDoesNotThrow(() -> history.recordFailure(" User@Example.test "));
    assertNull(history.recentFailures(user));
    when(clock.instant()).thenReturn(now.plusSeconds(599));
    assertNull(history.recentFailures(user));
    when(clock.instant()).thenReturn(now.plusSeconds(600));
    assertEquals(2, history.recentFailures(user));
  }

  @Test
  void disabledObservationAndMalformedEmailDoNotWriteOrReportSafeCounts() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    var disabled = new JdbcLoginRiskHistory(jdbc, Clock.systemUTC(), false);
    disabled.recordFailure("user@example.test");
    assertNull(disabled.recentFailures(UUID.randomUUID()));
    var enabled = new JdbcLoginRiskHistory(jdbc, Clock.systemUTC(), true);
    enabled.recordFailure(null);
    enabled.recordFailure("a".repeat(255));
    verifyNoInteractions(jdbc);
  }
}

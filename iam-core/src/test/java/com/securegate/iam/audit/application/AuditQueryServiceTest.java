package com.securegate.iam.audit.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.securegate.events.SecurityEvent;
import com.securegate.iam.audit.application.port.AuditEventQueries;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AuditQueryServiceTest {
  private static final Instant FROM = Instant.parse("2026-10-01T00:00:00Z");
  private static final Instant TO = FROM.plusSeconds(86400);
  private final AuditEventQueries queries = mock(AuditEventQueries.class);
  private final AuditQueryService service = new AuditQueryService(queries);

  @Test
  void limitPlusOneProducesBoundCursorAndAllowsDifferentPageSize() {
    var a = row(FROM.plusSeconds(3));
    var b = row(FROM.plusSeconds(2));
    var c = row(FROM.plusSeconds(1));
    when(queries.find(any())).thenReturn(List.of(a, b, c));
    var page = service.list(FROM, TO, "test.query", "synthetic", "actor", "correlation", 2, null);
    assertEquals(List.of(a, b), page.items());
    assertNotNull(page.nextCursor());
    assertThrows(UnsupportedOperationException.class, () -> page.items().clear());
    when(queries.find(any())).thenReturn(List.of(c));
    var next =
        service.list(
            FROM, TO, "test.query", "synthetic", "actor", "correlation", 1, page.nextCursor());
    assertEquals(List.of(c), next.items());
    assertNull(next.nextCursor());
    var captured = ArgumentCaptor.forClass(AuditQuery.class);
    verify(queries, times(2)).find(captured.capture());
    assertEquals(new AuditQuery.Position(b.occurredAt(), b.id()), captured.getValue().after());
  }

  @Test
  void emptyAndExactLimitPagesHaveNoContinuationAndFailuresAreSanitized() {
    when(queries.find(any())).thenReturn(List.of());
    assertTrue(service.list(FROM, TO, null, null, null, null, 1, null).items().isEmpty());
    when(queries.find(any())).thenReturn(List.of(row(FROM)));
    assertNull(service.list(FROM, TO, null, null, null, null, 1, null).nextCursor());
    when(queries.find(any())).thenThrow(new IllegalStateException("SQL/private-data"));
    var failure =
        assertThrows(
            AuditQueryUnavailableException.class,
            () -> service.list(FROM, TO, null, null, null, null, 1, null));
    assertFalse(failure.getMessage().contains("private-data"));
    assertNull(failure.getCause());
  }

  @Test
  void invalidWindowsLimitsAndFiltersNeverReachTheDatabase() {
    for (var bounds :
        new Instant[][] {
          {null, TO},
          {FROM, null},
          {TO, FROM},
          {FROM, FROM},
          {Instant.EPOCH.minusSeconds(1), Instant.EPOCH},
          {FROM, FROM.plusSeconds(32 * 86400)},
          {FROM.plusNanos(1), TO},
          {FROM, TO.plusNanos(1)},
          {Instant.parse("9997-12-31T00:00:00Z"), Instant.parse("9998-01-02T00:00:00Z")}
        }) {
      assertThrows(
          IllegalArgumentException.class,
          () -> service.list(bounds[0], bounds[1], null, null, null, null, 20, null));
    }
    for (int limit : new int[] {0, 101}) {
      assertThrows(
          IllegalArgumentException.class,
          () -> service.list(FROM, TO, null, null, null, null, limit, null));
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> service.list(FROM, TO, "bad type", null, null, null, 20, null));
    for (String bad : new String[] {"", " ", "line\nbreak", "x".repeat(201)}) {
      assertThrows(
          IllegalArgumentException.class,
          () -> service.list(FROM, TO, null, bad, null, null, 20, null));
      assertThrows(
          IllegalArgumentException.class,
          () -> service.list(FROM, TO, null, null, bad, null, 20, null));
      assertThrows(
          IllegalArgumentException.class,
          () -> service.list(FROM, TO, null, null, null, bad, 20, null));
    }
    verifyNoInteractions(queries);
  }

  @Test
  void malformedWrongVersionAndChangedFiltersRejectCursorBeforeQuery() {
    var query = new AuditQuery(FROM, TO, null, null, null, null, 20, null);
    var token = AuditCursor.encode(query, new AuditQuery.Position(FROM, UUID.randomUUID()));
    for (String bad :
        new String[] {
          "",
          "!",
          "A".repeat(257),
          "a",
          token + "=",
          encoded("2|" + FROM + "|" + UUID.randomUUID() + "|invalid"),
          encoded("1|too|few")
        }) {
      assertThrows(
          IllegalArgumentException.class,
          () -> service.list(FROM, TO, null, null, null, null, 20, bad));
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> service.list(FROM, TO, "test.changed", null, null, null, 20, token));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.list(FROM, TO, null, "different", null, null, 20, token));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.list(FROM.plusSeconds(1), TO, null, null, null, null, 20, token));
    verifyNoInteractions(queries);
  }

  @Test
  void cursorPositionsMustHaveMicrosecondPrecisionAndStayInWindow() {
    assertThrows(
        IllegalArgumentException.class, () -> new AuditQuery.Position(null, UUID.randomUUID()));
    assertThrows(IllegalArgumentException.class, () -> new AuditQuery.Position(FROM, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new AuditQuery.Position(FROM.plusNanos(1), UUID.randomUUID()));
    var query = new AuditQuery(FROM, TO, null, null, null, null, 20, null);
    for (Instant invalid : List.of(FROM.minusSeconds(1), TO)) {
      String token = AuditCursor.encode(query, new AuditQuery.Position(invalid, UUID.randomUUID()));
      assertThrows(
          IllegalArgumentException.class,
          () -> service.list(FROM, TO, null, null, null, null, 20, token));
    }
  }

  private static AuditEventView row(Instant at) {
    var event =
        new SecurityEvent(
            UUID.randomUUID(),
            "test.query",
            1,
            at,
            "iam-core",
            "correlation",
            "actor",
            Map.of("resource", "synthetic"),
            Map.of());
    return new AuditEventView(event.eventId(), at, at.plusSeconds(1), event);
  }

  private static String encoded(String text) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(text.getBytes(StandardCharsets.UTF_8));
  }
}

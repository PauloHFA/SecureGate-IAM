package com.securegate.iam.audit.application;

import com.securegate.iam.audit.application.port.AuditEventQueries;
import java.time.Instant;
import java.util.List;

public final class AuditQueryService {
  public record Page(List<AuditEventView> items, String nextCursor) {
    public Page {
      items = List.copyOf(items);
    }
  }

  private final AuditEventQueries queries;

  public AuditQueryService(AuditEventQueries queries) {
    this.queries = queries;
  }

  public Page list(
      Instant from,
      Instant to,
      String eventType,
      String resource,
      String actor,
      String correlationId,
      int limit,
      String cursor) {
    var query = new AuditQuery(from, to, eventType, resource, actor, correlationId, limit, null);
    if (cursor != null) {
      query =
          new AuditQuery(
              from,
              to,
              eventType,
              resource,
              actor,
              correlationId,
              limit,
              AuditCursor.decode(cursor, query));
    }
    List<AuditEventView> rows;
    try {
      rows = queries.find(query);
    } catch (RuntimeException unavailable) {
      throw new AuditQueryUnavailableException();
    }
    boolean more = rows.size() > limit;
    var items = rows.subList(0, Math.min(limit, rows.size()));
    String next = null;
    if (more) {
      var last = items.getLast();
      next = AuditCursor.encode(query, new AuditQuery.Position(last.occurredAt(), last.id()));
    }
    return new Page(items, next);
  }
}

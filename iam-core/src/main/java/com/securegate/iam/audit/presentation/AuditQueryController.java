package com.securegate.iam.audit.presentation;

import com.securegate.iam.audit.application.AuditQueryService;
import com.securegate.iam.audit.application.AuditQueryUnavailableException;
import com.securegate.iam.shared.presentation.ProblemResponses;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/audit/events")
@ConditionalOnProperty(name = "iam.audit.query.enabled", havingValue = "true")
public class AuditQueryController {
  private final AuditQueryService service;
  private final ProblemResponses problems;

  public AuditQueryController(AuditQueryService service, ProblemResponses problems) {
    this.service = service;
    this.problems = problems;
  }

  @GetMapping
  ResponseEntity<AuditQueryService.Page> list(
      @RequestParam String from,
      @RequestParam String to,
      @RequestParam(required = false) String eventType,
      @RequestParam(required = false) String resource,
      @RequestParam(required = false) String actor,
      @RequestParam(required = false) String correlationId,
      @RequestParam(defaultValue = "20") int limit,
      @RequestParam(required = false) String cursor,
      HttpServletRequest request) {
    var allowed =
        java.util.Set.of(
            "from", "to", "eventType", "resource", "actor", "correlationId", "limit", "cursor");
    request
        .getParameterMap()
        .forEach(
            (name, values) -> {
              if (!allowed.contains(name) || values.length != 1) {
                throw new IllegalArgumentException("Invalid audit query parameters.");
              }
            });
    return ResponseEntity.ok()
        .header("Cache-Control", "no-store")
        .body(
            service.list(
                parse(from), parse(to), eventType, resource, actor, correlationId, limit, cursor));
  }

  private static Instant parse(String value) {
    try {
      return Instant.parse(value);
    } catch (java.time.DateTimeException invalid) {
      throw new IllegalArgumentException("Invalid audit query time.");
    }
  }

  @ExceptionHandler(AuditQueryUnavailableException.class)
  ResponseEntity<Object> unavailable(
      AuditQueryUnavailableException ignored, HttpServletRequest request) {
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .header("Cache-Control", "no-store")
        .body(problems.create(HttpStatus.SERVICE_UNAVAILABLE, request));
  }
}

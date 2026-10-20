package com.securegate.iam.authorization.presentation;

import com.securegate.iam.authorization.application.PrivilegedRequestService;
import com.securegate.iam.authorization.application.ResourceAccessService;
import com.securegate.iam.shared.presentation.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/pam/requests")
@ConditionalOnProperty(name = "iam.pam.enabled", havingValue = "true")
public class PrivilegedRequestController {
  private final PrivilegedRequestService service;

  public PrivilegedRequestController(PrivilegedRequestService service) {
    this.service = service;
  }

  @PostMapping
  ResponseEntity<PrivilegedRequestService.View> create(
      @Valid @RequestBody Request body,
      @AuthenticationPrincipal OAuth2AuthenticatedPrincipal principal,
      HttpServletRequest request) {
    ResourceAccessService.Permit permit = principal.getAttribute("pam_permit");
    return ResponseEntity.status(201)
        .header("Cache-Control", "no-store")
        .body(
            service.create(
                permit,
                body.justification(),
                body.ttlSeconds(),
                (String) request.getAttribute(CorrelationIdFilter.ATTRIBUTE)));
  }

  @GetMapping("/{id}")
  ResponseEntity<PrivilegedRequestService.View> get(
      @PathVariable UUID id, @AuthenticationPrincipal OAuth2AuthenticatedPrincipal principal) {
    return ResponseEntity.ok()
        .header("Cache-Control", "no-store")
        .body(service.get(id, UUID.fromString(principal.getName())));
  }

  @DeleteMapping("/{id}")
  ResponseEntity<PrivilegedRequestService.View> revoke(
      @PathVariable UUID id,
      @AuthenticationPrincipal OAuth2AuthenticatedPrincipal principal,
      HttpServletRequest request) {
    return ResponseEntity.ok()
        .header("Cache-Control", "no-store")
        .body(
            service.revoke(
                id,
                UUID.fromString(principal.getName()),
                (String) request.getAttribute(CorrelationIdFilter.ATTRIBUTE)));
  }

  public record Request(
      @NotBlank @Size(min = 10, max = 500) String justification,
      @NotNull @Min(30) @Max(300) Integer ttlSeconds) {
    @Override
    public String toString() {
      return "PrivilegedRequestBody[redacted]";
    }
  }

  @ExceptionHandler(ResourceAccessService.Rejected.class)
  ResponseEntity<java.util.Map<String, String>> rejected() {
    return ResponseEntity.status(403)
        .header("Cache-Control", "no-store")
        .body(java.util.Map.of("error", "insufficient_scope"));
  }
}

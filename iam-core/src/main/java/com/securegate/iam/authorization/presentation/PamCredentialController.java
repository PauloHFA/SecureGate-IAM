package com.securegate.iam.authorization.presentation;

import com.securegate.iam.authorization.application.PamCredentialService;
import com.securegate.iam.authorization.application.ResourceAccessService;
import com.securegate.iam.shared.presentation.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/pam/requests/{id}/credentials")
@ConditionalOnProperty(name = "iam.pam.credentials.enabled", havingValue = "true")
public class PamCredentialController {
  private final PamCredentialService service;

  public PamCredentialController(PamCredentialService service) {
    this.service = service;
  }

  @PostMapping
  ResponseEntity<PamCredentialService.Secret> issue(
      @PathVariable UUID id, BearerTokenAuthentication authentication, HttpServletRequest request) {
    return ResponseEntity.status(201)
        .header("Cache-Control", "no-store")
        .body(
            service.issue(
                id,
                authentication.getToken().getTokenValue(),
                request.getRemoteAddr(),
                (String) request.getAttribute(CorrelationIdFilter.ATTRIBUTE)));
  }

  @GetMapping
  ResponseEntity<PamCredentialService.Metadata> get(
      @PathVariable UUID id, @AuthenticationPrincipal OAuth2AuthenticatedPrincipal principal) {
    return ResponseEntity.ok()
        .header("Cache-Control", "no-store")
        .body(service.get(id, UUID.fromString(principal.getName())));
  }

  @ExceptionHandler(PamCredentialService.Unavailable.class)
  ResponseEntity<Map<String, String>> unavailable() {
    return ResponseEntity.status(503)
        .header("Cache-Control", "no-store")
        .header("Retry-After", "1")
        .body(Map.of("error", "temporarily_unavailable"));
  }

  @ExceptionHandler(ResourceAccessService.Rejected.class)
  ResponseEntity<Map<String, String>> rejected() {
    return ResponseEntity.status(403)
        .header("Cache-Control", "no-store")
        .body(Map.of("error", "insufficient_scope"));
  }
}

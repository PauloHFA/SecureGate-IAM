package com.securegate.iam.authorization.presentation;

import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Demonstration of a self-only operation: no caller-selected subject or policy target. */
@RestController
@ConditionalOnProperty(name = "iam.authorization.enabled", havingValue = "true")
public class ResourceController {
  @GetMapping("/api/resources/account")
  public ResponseEntity<Map<String, Object>> account(
      @AuthenticationPrincipal OAuth2AuthenticatedPrincipal principal) {
    return ResponseEntity.ok().header("Cache-Control", "no-store").body(principal.getAttributes());
  }
}

package com.securegate.iam.authorization.presentation;

import com.securegate.iam.authorization.application.port.AuthorizationContinuation;
import com.securegate.iam.authorization.application.port.ConsentRequests;
import com.securegate.iam.identity.application.port.AuthenticatedIdentity;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "iam.authorization.enabled", havingValue = "true")
public class AuthorizationResumeController {
  private final AuthorizationContinuation continuations;

  public AuthorizationResumeController(AuthorizationContinuation continuations) {
    this.continuations = continuations;
  }

  @PostMapping("/oauth2/continue")
  ResponseEntity<Void> resume(
      Authentication authentication, HttpServletRequest request, @RequestParam String ticket) {
    if (!(authentication.getPrincipal() instanceof AuthenticatedIdentity identity)
        || ticket.length() > 64) {
      throw new AuthorizationContinuation.Unavailable();
    }
    var session = request.getSession(false);
    if (session == null
        || !(session.getAttribute(ConsentRequests.SESSION_ID) instanceof String sid)) {
      throw new AuthorizationContinuation.Unavailable();
    }
    var parameters =
        continuations.resume(
            ticket, UUID.fromString(identity.getName()), identity.version(), UUID.fromString(sid));
    String query =
        parameters.entrySet().stream()
            .sorted(java.util.Map.Entry.comparingByKey())
            .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
            .collect(java.util.stream.Collectors.joining("&"));
    return ResponseEntity.status(303)
        .location(URI.create("/oauth2/authorize?" + query))
        .header("Cache-Control", "no-store")
        .header("Referrer-Policy", "no-referrer")
        .build();
  }

  @ExceptionHandler(AuthorizationContinuation.Unavailable.class)
  ResponseEntity<Void> unavailable() {
    return ResponseEntity.status(400).header("Cache-Control", "no-store").build();
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}

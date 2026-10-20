package com.securegate.iam.authorization.presentation;

import com.securegate.iam.authorization.application.PrivilegedRequestService;
import com.securegate.iam.shared.presentation.OperationContexts;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name = "iam.pam.credentials.enabled", havingValue = "true")
public class PamAdministrationController {
  private final PrivilegedRequestService service;

  public PamAdministrationController(PrivilegedRequestService service) {
    this.service = service;
  }

  @PostMapping("/api/admin/pam/requests/{id}/revoke")
  ResponseEntity<PrivilegedRequestService.View> revoke(
      @PathVariable UUID id, Authentication actor, HttpServletRequest request) {
    return ResponseEntity.accepted()
        .header("Cache-Control", "no-store")
        .body(service.revokeAdministratively(id, OperationContexts.from(actor, request)));
  }
}

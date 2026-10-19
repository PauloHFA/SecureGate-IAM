package com.securegate.iam.policy.presentation;

import com.securegate.iam.policy.application.RiskAdministration;
import com.securegate.iam.policy.application.port.RiskEvidence;
import com.securegate.iam.shared.presentation.OperationContexts;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/risk")
public class RiskAdministrationController {
  private final RiskAdministration risk;

  public RiskAdministrationController(RiskAdministration risk) {
    this.risk = risk;
  }

  @PostMapping("/resources")
  ResponseEntity<RiskEvidence.ResourceView> createResource(
      @Valid @RequestBody Resource body, Authentication actor, HttpServletRequest request) {
    return created(
        risk.createResource(
            body.resource(), body.sensitivity(), OperationContexts.from(actor, request)));
  }

  @GetMapping("/resources")
  ResponseEntity<RiskEvidence.ResourceView> resource(@RequestParam String resource) {
    return ok(risk.resource(resource));
  }

  @PutMapping("/resources")
  ResponseEntity<RiskEvidence.ResourceView> updateResource(
      @Valid @RequestBody ResourceUpdate body, Authentication actor, HttpServletRequest request) {
    return ok(
        risk.updateResource(
            body.resource(),
            body.sensitivity(),
            body.version(),
            OperationContexts.from(actor, request)));
  }

  @PostMapping("/networks")
  ResponseEntity<RiskEvidence.NetworkView> createNetwork(
      @Valid @RequestBody Network body, Authentication actor, HttpServletRequest request) {
    return created(risk.createNetwork(body.cidr(), OperationContexts.from(actor, request)));
  }

  @GetMapping("/networks")
  ResponseEntity<List<RiskEvidence.NetworkView>> networks() {
    return ok(risk.networks());
  }

  @PutMapping("/networks/{id}")
  ResponseEntity<RiskEvidence.NetworkView> updateNetwork(
      @PathVariable UUID id,
      @Valid @RequestBody NetworkUpdate body,
      Authentication actor,
      HttpServletRequest request) {
    return ok(
        risk.updateNetwork(
            id, body.enabled(), body.version(), OperationContexts.from(actor, request)));
  }

  @GetMapping("/users/{userId}/contexts")
  ResponseEntity<List<RiskEvidence.ContextView>> contexts(@PathVariable UUID userId) {
    return ok(risk.contexts(userId));
  }

  @GetMapping("/users/{userId}/devices/{deviceId}")
  ResponseEntity<RiskEvidence.DeviceView> device(
      @PathVariable UUID userId, @PathVariable UUID deviceId) {
    return ok(risk.device(userId, deviceId));
  }

  @PutMapping("/users/{userId}/devices/{deviceId}")
  ResponseEntity<RiskEvidence.DeviceView> trustDevice(
      @PathVariable UUID userId,
      @PathVariable UUID deviceId,
      @Valid @RequestBody Approval body,
      Authentication actor,
      HttpServletRequest request) {
    return ok(
        risk.trustDevice(
            userId,
            deviceId,
            body.trusted(),
            body.ttlSeconds(),
            body.version(),
            OperationContexts.from(actor, request)));
  }

  private static <T> ResponseEntity<T> ok(T body) {
    return ResponseEntity.ok().header("Cache-Control", "no-store").body(body);
  }

  private static <T> ResponseEntity<T> created(T body) {
    return ResponseEntity.status(201).header("Cache-Control", "no-store").body(body);
  }

  public record Resource(
      @NotBlank @Size(max = 128) String resource, @NotBlank String sensitivity) {}

  public record ResourceUpdate(
      @NotBlank @Size(max = 128) String resource,
      @NotBlank String sensitivity,
      @NotNull @Min(0) Long version) {}

  public record Network(@NotBlank @Size(max = 64) String cidr) {}

  public record NetworkUpdate(@NotNull Boolean enabled, @NotNull @Min(0) Long version) {}

  public record Approval(
      @NotNull Boolean trusted,
      @NotNull @Min(60) @Max(86400) Integer ttlSeconds,
      @NotNull @Min(0) Long version) {}
}

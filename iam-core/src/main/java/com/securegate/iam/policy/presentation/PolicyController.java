package com.securegate.iam.policy.presentation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securegate.iam.policy.application.DecisionView;
import com.securegate.iam.policy.application.PolicyService;
import com.securegate.iam.policy.application.PolicyView;
import com.securegate.iam.policy.application.SimulationSignals;
import com.securegate.iam.shared.application.port.PageRequest;
import com.securegate.iam.shared.presentation.OperationContexts;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/policies")
public class PolicyController {
  private final PolicyService policies;
  private final ObjectMapper json;

  public PolicyController(PolicyService policies, ObjectMapper json) {
    this.policies = policies;
    this.json = json;
  }

  @PostMapping
  ResponseEntity<PolicyResponse> create(
      @Valid @RequestBody CreatePolicy body, Authentication actor, HttpServletRequest request) {
    var result =
        policies.create(
            body.name(),
            body.priority(),
            body.enabled(),
            body.document().toString(),
            OperationContexts.from(actor, request));
    return ResponseEntity.created(URI.create("/api/admin/policies/" + result.id()))
        .header("Cache-Control", "no-store")
        .body(response(result));
  }

  @PutMapping("/{id}")
  ResponseEntity<PolicyResponse> update(
      @PathVariable UUID id,
      @Valid @RequestBody UpdatePolicy body,
      Authentication actor,
      HttpServletRequest request) {
    return noStore(
        response(
            policies.update(
                id,
                body.name(),
                body.priority(),
                body.enabled(),
                body.document().toString(),
                body.version(),
                OperationContexts.from(actor, request))));
  }

  @GetMapping("/{id}")
  ResponseEntity<PolicyResponse> get(@PathVariable UUID id) {
    return noStore(response(policies.get(id)));
  }

  @GetMapping
  ResponseEntity<List<PolicyResponse>> list(
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
      @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int offset) {
    return noStore(
        policies.list(new PageRequest(limit, offset)).stream().map(this::response).toList());
  }

  @PostMapping("/evaluate")
  ResponseEntity<DecisionView> evaluate(@Valid @RequestBody Evaluation body) {
    return noStore(
        policies.evaluate(body.userId(), body.resource(), body.action(), body.requestedScopes()));
  }

  private PolicyResponse response(PolicyView policy) {
    try {
      return new PolicyResponse(
          policy.id(),
          policy.name(),
          policy.priority(),
          policy.enabled(),
          policy.version(),
          json.readTree(policy.document()));
    } catch (JsonProcessingException failure) {
      throw new IllegalStateException("Invalid stored policy");
    }
  }

  @PostMapping("/simulate")
  ResponseEntity<DecisionView> simulate(@Valid @RequestBody Simulation body) {
    return noStore(
        policies.simulate(
            body.userId(),
            body.resource(),
            body.action(),
            body.requestedScopes(),
            body.scenario()));
  }

  private static <T> ResponseEntity<T> noStore(T body) {
    return ResponseEntity.ok().header("Cache-Control", "no-store").body(body);
  }

  public record CreatePolicy(
      @NotBlank @Size(max = 64) String name,
      @NotNull @Min(0) @Max(1000) Integer priority,
      @NotNull Boolean enabled,
      @NotNull JsonNode document) {}

  public record UpdatePolicy(
      @NotBlank @Size(max = 64) String name,
      @NotNull @Min(0) @Max(1000) Integer priority,
      @NotNull Boolean enabled,
      @NotNull JsonNode document,
      @NotNull @Min(0) Long version) {}

  public record Evaluation(
      @NotNull UUID userId,
      @NotBlank @Size(max = 128) String resource,
      @NotBlank @Size(max = 128) String action,
      @NotEmpty @Size(max = 10) Set<@NotBlank @Size(max = 64) String> requestedScopes) {}

  public record PolicyResponse(
      UUID id, String name, int priority, boolean enabled, long version, JsonNode document) {}

  public record Simulation(
      @NotNull UUID userId,
      @NotBlank @Size(max = 128) String resource,
      @NotBlank @Size(max = 128) String action,
      @NotEmpty @Size(max = 10) Set<@NotBlank @Size(max = 64) String> requestedScopes,
      @NotNull SimulationSignals scenario) {}
}

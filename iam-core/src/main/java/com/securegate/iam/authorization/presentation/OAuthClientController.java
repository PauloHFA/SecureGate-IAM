package com.securegate.iam.authorization.presentation;

import com.securegate.iam.authorization.application.ClientView;
import com.securegate.iam.authorization.application.OAuthClientService;
import com.securegate.iam.shared.presentation.OperationContexts;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/oauth-clients")
@ConditionalOnProperty(name = "iam.authorization.enabled", havingValue = "true")
public class OAuthClientController {
  private final OAuthClientService clients;

  public OAuthClientController(OAuthClientService clients) {
    this.clients = clients;
  }

  @PostMapping
  ResponseEntity<OAuthClientService.CreatedClient> create(
      @Valid @RequestBody CreateClientRequest body,
      Authentication actor,
      HttpServletRequest request) {
    var result =
        clients.create(
            body.clientId(),
            body.name(),
            body.audience(),
            body.scopes(),
            OperationContexts.from(actor, request));
    return ResponseEntity.created(
            URI.create("/api/admin/oauth-clients/" + result.client().clientId()))
        .header("Cache-Control", "no-store")
        .header("Pragma", "no-cache")
        .body(result);
  }

  @GetMapping("/{clientId}")
  ResponseEntity<ClientView> get(@PathVariable String clientId) {
    return ResponseEntity.ok().header("Cache-Control", "no-store").body(clients.get(clientId));
  }

  public record CreateClientRequest(
      @NotBlank @Size(max = 64) String clientId,
      @NotBlank @Size(max = 120) String name,
      @NotBlank @Size(max = 200) String audience,
      @NotEmpty @Size(max = 10) Set<@NotBlank @Size(max = 64) String> scopes) {}
}

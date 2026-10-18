package com.securegate.iam.identity.presentation;

import com.securegate.iam.identity.application.UserService;
import com.securegate.iam.identity.application.UserView;
import com.securegate.iam.shared.application.port.PageRequest;
import com.securegate.iam.shared.presentation.OperationContexts;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/users")
public class UserController {
  private final UserService users;

  public UserController(UserService users) {
    this.users = users;
  }

  @PostMapping
  ResponseEntity<UserView> create(
      @Valid @RequestBody CreateUserRequest body,
      Authentication actor,
      HttpServletRequest request) {
    UserView created =
        users.create(
            body.email(),
            body.displayName(),
            body.password(),
            OperationContexts.from(actor, request));
    return ResponseEntity.created(URI.create("/api/admin/users/" + created.id())).body(created);
  }

  @GetMapping("/{id}")
  UserView get(@PathVariable UUID id) {
    return users.get(id);
  }

  @GetMapping
  List<UserView> list(
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
      @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int offset) {
    return users.list(new PageRequest(limit, offset));
  }

  @PutMapping("/{id}")
  UserView update(
      @PathVariable UUID id,
      @Valid @RequestBody UpdateUserRequest body,
      Authentication actor,
      HttpServletRequest request) {
    return users.update(
        id,
        body.email(),
        body.displayName(),
        body.version(),
        OperationContexts.from(actor, request));
  }

  @PatchMapping("/{id}/status")
  UserView status(
      @PathVariable UUID id,
      @Valid @RequestBody StatusRequest body,
      Authentication actor,
      HttpServletRequest request) {
    return users.status(id, body.status(), body.version(), OperationContexts.from(actor, request));
  }

  @DeleteMapping("/{id}")
  ResponseEntity<Void> disable(
      @PathVariable UUID id,
      @RequestParam @PositiveOrZero long version,
      Authentication actor,
      HttpServletRequest request) {
    users.status(id, "DISABLED", version, OperationContexts.from(actor, request));
    return ResponseEntity.noContent().build();
  }

  @PutMapping("/{id}/password")
  ResponseEntity<Void> password(
      @PathVariable UUID id,
      @Valid @RequestBody PasswordRequest body,
      Authentication actor,
      HttpServletRequest request) {
    users.password(id, body.password(), body.version(), OperationContexts.from(actor, request));
    return ResponseEntity.noContent().build();
  }

  public record CreateUserRequest(
      @NotBlank @Email @Size(max = 254) String email,
      @NotBlank @Size(max = 120) String displayName,
      @NotBlank @Size(min = 12, max = 128) String password) {
    @Override
    public String toString() {
      return "CreateUserRequest[REDACTED]";
    }
  }

  public record UpdateUserRequest(
      @NotBlank @Email @Size(max = 254) String email,
      @NotBlank @Size(max = 120) String displayName,
      @NotNull @PositiveOrZero Long version) {}

  public record StatusRequest(@NotBlank String status, @NotNull @PositiveOrZero Long version) {}

  public record PasswordRequest(
      @NotBlank @Size(min = 12, max = 128) String password, @NotNull @PositiveOrZero Long version) {
    @Override
    public String toString() {
      return "PasswordRequest[REDACTED]";
    }
  }
}

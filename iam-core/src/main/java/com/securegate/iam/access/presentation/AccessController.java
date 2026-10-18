package com.securegate.iam.access.presentation;

import com.securegate.iam.access.application.AccessService;
import com.securegate.iam.access.application.Assignment;
import com.securegate.iam.access.application.CatalogItem;
import com.securegate.iam.access.application.PermissionView;
import com.securegate.iam.shared.application.port.PageRequest;
import com.securegate.iam.shared.presentation.OperationContexts;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin")
public class AccessController {
  private final AccessService access;

  public AccessController(AccessService access) {
    this.access = access;
  }

  @PostMapping("/roles")
  ResponseEntity<CatalogItem> role(
      @Valid @RequestBody NameRequest body, Authentication actor, HttpServletRequest request) {
    CatalogItem created = access.createRole(body.name(), OperationContexts.from(actor, request));
    return ResponseEntity.created(URI.create("/api/admin/roles/" + created.id())).body(created);
  }

  @PostMapping("/groups")
  ResponseEntity<CatalogItem> group(
      @Valid @RequestBody NameRequest body, Authentication actor, HttpServletRequest request) {
    CatalogItem created = access.createGroup(body.name(), OperationContexts.from(actor, request));
    return ResponseEntity.created(URI.create("/api/admin/groups/" + created.id())).body(created);
  }

  @PostMapping("/permissions")
  ResponseEntity<PermissionView> permission(
      @Valid @RequestBody PermissionRequest body,
      Authentication actor,
      HttpServletRequest request) {
    PermissionView created =
        access.createPermission(
            body.resource(), body.action(), OperationContexts.from(actor, request));
    return ResponseEntity.created(URI.create("/api/admin/permissions/" + created.id()))
        .body(created);
  }

  @GetMapping("/roles/{id}")
  CatalogItem role(@PathVariable UUID id) {
    return access.role(id);
  }

  @GetMapping("/groups/{id}")
  CatalogItem group(@PathVariable UUID id) {
    return access.group(id);
  }

  @GetMapping("/permissions/{id}")
  PermissionView permission(@PathVariable UUID id) {
    return access.permission(id);
  }

  @GetMapping("/roles")
  List<CatalogItem> roles(
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
      @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int offset) {
    return access.roles(new PageRequest(limit, offset));
  }

  @GetMapping("/groups")
  List<CatalogItem> groups(
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
      @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int offset) {
    return access.groups(new PageRequest(limit, offset));
  }

  @GetMapping("/permissions")
  List<PermissionView> permissions(
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
      @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int offset) {
    return access.permissions(new PageRequest(limit, offset));
  }

  @GetMapping("/users/{userId}/permissions")
  Set<String> effective(@PathVariable UUID userId) {
    return access.effectivePermissions(userId);
  }

  @PutMapping("/users/{userId}/roles/{roleId}")
  ResponseEntity<Void> userRole(
      @PathVariable UUID userId,
      @PathVariable UUID roleId,
      Authentication actor,
      HttpServletRequest request) {
    return assign(Assignment.USER_ROLE, userId, roleId, true, actor, request);
  }

  @DeleteMapping("/users/{userId}/roles/{roleId}")
  ResponseEntity<Void> revokeUserRole(
      @PathVariable UUID userId,
      @PathVariable UUID roleId,
      Authentication actor,
      HttpServletRequest request) {
    return assign(Assignment.USER_ROLE, userId, roleId, false, actor, request);
  }

  @PutMapping("/groups/{groupId}/members/{userId}")
  ResponseEntity<Void> groupMember(
      @PathVariable UUID groupId,
      @PathVariable UUID userId,
      Authentication actor,
      HttpServletRequest request) {
    return assign(Assignment.GROUP_MEMBER, groupId, userId, true, actor, request);
  }

  @DeleteMapping("/groups/{groupId}/members/{userId}")
  ResponseEntity<Void> revokeGroupMember(
      @PathVariable UUID groupId,
      @PathVariable UUID userId,
      Authentication actor,
      HttpServletRequest request) {
    return assign(Assignment.GROUP_MEMBER, groupId, userId, false, actor, request);
  }

  @PutMapping("/groups/{groupId}/roles/{roleId}")
  ResponseEntity<Void> groupRole(
      @PathVariable UUID groupId,
      @PathVariable UUID roleId,
      Authentication actor,
      HttpServletRequest request) {
    return assign(Assignment.GROUP_ROLE, groupId, roleId, true, actor, request);
  }

  @DeleteMapping("/groups/{groupId}/roles/{roleId}")
  ResponseEntity<Void> revokeGroupRole(
      @PathVariable UUID groupId,
      @PathVariable UUID roleId,
      Authentication actor,
      HttpServletRequest request) {
    return assign(Assignment.GROUP_ROLE, groupId, roleId, false, actor, request);
  }

  @PutMapping("/roles/{roleId}/permissions/{permissionId}")
  ResponseEntity<Void> rolePermission(
      @PathVariable UUID roleId,
      @PathVariable UUID permissionId,
      Authentication actor,
      HttpServletRequest request) {
    return assign(Assignment.ROLE_PERMISSION, roleId, permissionId, true, actor, request);
  }

  @DeleteMapping("/roles/{roleId}/permissions/{permissionId}")
  ResponseEntity<Void> revokeRolePermission(
      @PathVariable UUID roleId,
      @PathVariable UUID permissionId,
      Authentication actor,
      HttpServletRequest request) {
    return assign(Assignment.ROLE_PERMISSION, roleId, permissionId, false, actor, request);
  }

  private ResponseEntity<Void> assign(
      Assignment kind,
      UUID left,
      UUID right,
      boolean grant,
      Authentication actor,
      HttpServletRequest request) {
    access.assign(kind, left, right, grant, OperationContexts.from(actor, request));
    return ResponseEntity.noContent().build();
  }

  public record NameRequest(@NotBlank @Size(max = 64) String name) {}

  public record PermissionRequest(
      @NotBlank @Size(max = 64) String resource, @NotBlank @Size(max = 64) String action) {}
}

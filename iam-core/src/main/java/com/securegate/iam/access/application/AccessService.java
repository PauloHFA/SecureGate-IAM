package com.securegate.iam.access.application;

import com.securegate.events.SecurityEventType;
import com.securegate.iam.access.application.port.AccessRepository;
import com.securegate.iam.access.domain.Group;
import com.securegate.iam.access.domain.Permission;
import com.securegate.iam.access.domain.Role;
import com.securegate.iam.audit.application.port.AuditRecord;
import com.securegate.iam.audit.application.port.AuditTrail;
import com.securegate.iam.identity.application.port.IdentityLookup;
import com.securegate.iam.shared.application.ResourceNotFoundException;
import com.securegate.iam.shared.application.port.OperationContext;
import com.securegate.iam.shared.application.port.PageRequest;
import com.securegate.iam.shared.application.port.UnitOfWork;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class AccessService {
  private final AccessRepository access;
  private final IdentityLookup identities;
  private final AuditTrail audit;
  private final UnitOfWork transactions;

  public AccessService(
      AccessRepository access,
      IdentityLookup identities,
      AuditTrail audit,
      UnitOfWork transactions) {
    this.access = access;
    this.identities = identities;
    this.audit = audit;
    this.transactions = transactions;
  }

  public CatalogItem createRole(String name, OperationContext context) {
    Role role = new Role(UUID.randomUUID(), name);
    return transactions.execute(
        () -> {
          access.createRole(role);
          record("access.role.created", role.id(), context, Map.of());
          return new CatalogItem(role.id(), role.name());
        });
  }

  public CatalogItem createGroup(String name, OperationContext context) {
    Group group = new Group(UUID.randomUUID(), name);
    return transactions.execute(
        () -> {
          access.createGroup(group);
          record("access.group.created", group.id(), context, Map.of());
          return new CatalogItem(group.id(), group.name());
        });
  }

  public PermissionView createPermission(String resource, String action, OperationContext context) {
    Permission permission = new Permission(UUID.randomUUID(), resource, action);
    return transactions.execute(
        () -> {
          access.createPermission(permission);
          record("access.permission.created", permission.id(), context, Map.of());
          return PermissionView.from(permission);
        });
  }

  public CatalogItem role(UUID id) {
    Role role = access.role(id).orElseThrow(ResourceNotFoundException::new);
    return new CatalogItem(role.id(), role.name());
  }

  public CatalogItem group(UUID id) {
    Group group = access.group(id).orElseThrow(ResourceNotFoundException::new);
    return new CatalogItem(group.id(), group.name());
  }

  public PermissionView permission(UUID id) {
    return PermissionView.from(access.permission(id).orElseThrow(ResourceNotFoundException::new));
  }

  public List<CatalogItem> roles(PageRequest page) {
    return access.roles(page).stream().map(r -> new CatalogItem(r.id(), r.name())).toList();
  }

  public List<CatalogItem> groups(PageRequest page) {
    return access.groups(page).stream().map(g -> new CatalogItem(g.id(), g.name())).toList();
  }

  public List<PermissionView> permissions(PageRequest page) {
    return access.permissions(page).stream().map(PermissionView::from).toList();
  }

  public void assign(
      Assignment kind, UUID left, UUID right, boolean grant, OperationContext context) {
    transactions.execute(
        () -> {
          switch (kind) {
            case USER_ROLE -> {
              requireUser(left, grant);
              requireRole(right);
            }
            case GROUP_MEMBER -> {
              requireGroup(left);
              requireUser(right, grant);
            }
            case GROUP_ROLE -> {
              requireGroup(left);
              requireRole(right);
            }
            case ROLE_PERMISSION -> {
              requireRole(left);
              requirePermission(right);
            }
          }
          if (access.assign(kind, left, right, grant)) {
            record(
                grant ? "access.assignment.granted" : "access.assignment.revoked",
                left,
                context,
                Map.of("kind", kind.name(), "targetId", right.toString()));
          }
          return null;
        });
  }

  public Set<String> effectivePermissions(UUID userId) {
    if (!identities.isActive(userId)) {
      return Set.of();
    }
    return Set.copyOf(access.effectivePermissions(userId));
  }

  private void requireUser(UUID id, boolean grant) {
    if (!identities.exists(id)) {
      throw new ResourceNotFoundException();
    }
    if (grant && !identities.isActive(id)) {
      throw new IllegalArgumentException("User is not active");
    }
  }

  private void requireRole(UUID id) {
    access.role(id).orElseThrow(ResourceNotFoundException::new);
  }

  private void requireGroup(UUID id) {
    access.group(id).orElseThrow(ResourceNotFoundException::new);
  }

  private void requirePermission(UUID id) {
    access.permission(id).orElseThrow(ResourceNotFoundException::new);
  }

  private void record(
      String type, UUID resource, OperationContext context, Map<String, String> details) {
    audit.record(
        new AuditRecord(new SecurityEventType(type), resource.toString(), context, details));
  }
}

package com.securegate.iam.access.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.securegate.iam.access.application.port.AccessRepository;
import com.securegate.iam.access.domain.*;
import com.securegate.iam.audit.application.port.AuditTrail;
import com.securegate.iam.identity.application.port.IdentityLookup;
import com.securegate.iam.shared.application.ResourceNotFoundException;
import com.securegate.iam.shared.application.port.*;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class AccessServiceTest {
  private final AccessRepository repository = mock(AccessRepository.class);
  private final IdentityLookup identities = mock(IdentityLookup.class);
  private final AuditTrail audit = mock(AuditTrail.class);
  private final UnitOfWork transactions =
      new UnitOfWork() {
        public <T> T execute(Supplier<T> task) {
          return task.get();
        }
      };
  private final AccessService service =
      new AccessService(repository, identities, audit, transactions);
  private final OperationContext context = new OperationContext("test-admin", "test-123");
  private final UUID left = UUID.randomUUID(), right = UUID.randomUUID();
  private final Role role = new Role(left, "ADMIN");
  private final Group group = new Group(left, "FINANCE");
  private final Permission permission = new Permission(right, "invoice", "read");

  private void resourcesExist() {
    when(repository.role(any())).thenReturn(Optional.of(role));
    when(repository.group(any())).thenReturn(Optional.of(group));
    when(repository.permission(any())).thenReturn(Optional.of(permission));
    when(identities.exists(any())).thenReturn(true);
    when(identities.isActive(any())).thenReturn(true);
  }

  @Test
  void createsNormalizedCatalogsWithAudit() {
    assertEquals("ADMIN", service.createRole("admin", context).name());
    assertEquals("FINANCE", service.createGroup("finance", context).name());
    assertEquals("invoice:read", service.createPermission("Invoice", "Read", context).key());
    verify(repository).createRole(any());
    verify(repository).createGroup(any());
    verify(repository).createPermission(any());
    verify(audit, times(3)).record(any());
  }

  @Test
  void readsAndListsCatalogsWithoutExposingDomainObjects() {
    resourcesExist();
    PageRequest page = new PageRequest(10, 0);
    when(repository.roles(page)).thenReturn(List.of(role));
    when(repository.groups(page)).thenReturn(List.of(group));
    when(repository.permissions(page)).thenReturn(List.of(permission));
    assertEquals(role.id(), service.role(left).id());
    assertEquals(group.id(), service.group(left).id());
    assertEquals(permission.id(), service.permission(right).id());
    assertEquals("ADMIN", service.roles(page).getFirst().name());
    assertEquals("FINANCE", service.groups(page).getFirst().name());
    PermissionView result = service.permissions(page).getFirst();
    assertEquals("invoice", result.resource());
    assertEquals("read", result.action());
    assertEquals("invoice:read", result.key());
  }

  @ParameterizedTest
  @EnumSource(Assignment.class)
  void grantsAndRevokesEachRelationshipWithAudit(Assignment kind) {
    resourcesExist();
    when(repository.assign(any(), any(), any(), anyBoolean())).thenReturn(true);
    service.assign(kind, left, right, true, context);
    service.assign(kind, left, right, false, context);
    verify(audit)
        .record(
            argThat(
                event ->
                    event.type().value().equals("access.assignment.granted")
                        && event.details().get("kind").equals(kind.name())));
    verify(audit)
        .record(argThat(event -> event.type().value().equals("access.assignment.revoked")));
  }

  @Test
  void doesNotAuditIdempotentRelationshipRequests() {
    resourcesExist();
    service.assign(Assignment.USER_ROLE, left, right, true, context);
    verifyNoInteractions(audit);
  }

  @Test
  void rejectsMissingCatalogsAndInactiveOrUnknownIdentities() {
    assertThrows(ResourceNotFoundException.class, () -> service.role(left));
    assertThrows(ResourceNotFoundException.class, () -> service.group(left));
    assertThrows(ResourceNotFoundException.class, () -> service.permission(left));
    assertThrows(
        ResourceNotFoundException.class,
        () -> service.assign(Assignment.USER_ROLE, left, right, true, context));
    when(identities.exists(left)).thenReturn(true);
    assertThrows(
        IllegalArgumentException.class,
        () -> service.assign(Assignment.USER_ROLE, left, right, true, context));
    resourcesExist();
    when(repository.role(any())).thenReturn(Optional.empty());
    assertThrows(
        ResourceNotFoundException.class,
        () -> service.assign(Assignment.USER_ROLE, left, right, true, context));
    assertThrows(
        ResourceNotFoundException.class,
        () -> service.assign(Assignment.GROUP_ROLE, left, right, true, context));
    when(repository.role(any())).thenReturn(Optional.of(role));
    when(repository.permission(any())).thenReturn(Optional.empty());
    assertThrows(
        ResourceNotFoundException.class,
        () -> service.assign(Assignment.ROLE_PERMISSION, left, right, true, context));
    when(repository.group(any())).thenReturn(Optional.empty());
    assertThrows(
        ResourceNotFoundException.class,
        () -> service.assign(Assignment.GROUP_MEMBER, left, right, true, context));
    verify(repository, never()).assign(any(), any(), any(), anyBoolean());
    verifyNoInteractions(audit);
  }

  @Test
  void returnsOnlyEffectivePermissionsForActiveIdentities() {
    when(identities.isActive(left)).thenReturn(true);
    when(repository.effectivePermissions(left)).thenReturn(Set.of("invoice:read"));
    assertEquals(Set.of("invoice:read"), service.effectivePermissions(left));
    when(identities.isActive(left)).thenReturn(false);
    assertTrue(service.effectivePermissions(left).isEmpty());
    verify(repository, times(1)).effectivePermissions(left);
  }

  @Test
  void allowsRevocationForAnExistingInactiveUser() {
    resourcesExist();
    when(identities.isActive(left)).thenReturn(false);
    service.assign(Assignment.USER_ROLE, left, right, false, context);
    verify(repository).assign(Assignment.USER_ROLE, left, right, false);
  }
}

package com.securegate.iam.identity.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.securegate.iam.audit.application.port.AuditTrail;
import com.securegate.iam.identity.application.port.*;
import com.securegate.iam.identity.domain.*;
import com.securegate.iam.shared.application.ConcurrentUpdateException;
import com.securegate.iam.shared.application.ResourceNotFoundException;
import com.securegate.iam.shared.application.port.*;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class UserServiceTest {
  private final UserRepository repository = mock(UserRepository.class);
  private final CredentialRepository credentials = mock(CredentialRepository.class);
  private final PasswordHasher passwords = mock(PasswordHasher.class);
  private final AuditTrail audit = mock(AuditTrail.class);
  private final UnitOfWork transaction =
      new UnitOfWork() {
        public <T> T execute(Supplier<T> task) {
          return task.get();
        }
      };
  private final UserService service =
      new UserService(repository, credentials, passwords, audit, transaction);
  private final OperationContext context = new OperationContext("test-admin", "test-123");
  private final User user = User.register(UUID.randomUUID(), "user@example.test", "Name");

  @Test
  void createsUserAndSeparateCredentialAndSafeAuditInAUnitOfWork() {
    when(passwords.hash("Long-test-password")).thenReturn("encoded-hash");
    UserView created = service.create("User@Example.test", "Name", "Long-test-password", context);
    assertEquals("user@example.test", created.email());
    assertEquals("ACTIVE", created.status());
    assertEquals(0, created.version());
    verify(repository).create(argThat(value -> value.id().equals(created.id())));
    verify(credentials).create(created.id(), "encoded-hash");
    verify(audit)
        .record(
            argThat(
                event ->
                    event.type().value().equals("identity.user.created")
                        && !event.details().toString().contains("password")
                        && event.context().equals(context)));
  }

  @Test
  void readsListsAndLooksUpIdentityState() {
    when(repository.find(user.id())).thenReturn(Optional.of(user));
    when(repository.list(any())).thenReturn(List.of(user));
    assertEquals(user.id(), service.get(user.id()).id());
    assertEquals("Name", service.get(user.id()).displayName());
    assertEquals(1, service.list(new PageRequest(20, 0)).size());
    assertTrue(service.exists(user.id()));
    assertTrue(service.isActive(user.id()));
    assertTrue(service.isCurrent(user.id(), 0));
    assertFalse(service.isCurrent(user.id(), 1));
    when(repository.find(user.id())).thenReturn(Optional.of(user.transition(UserStatus.LOCKED)));
    assertFalse(service.isActive(user.id()));
    when(repository.find(user.id())).thenReturn(Optional.empty());
    assertFalse(service.exists(user.id()));
    assertFalse(service.isActive(user.id()));
    assertThrows(ResourceNotFoundException.class, () -> service.get(user.id()));
  }

  @Test
  void updatesProfileAndAuditsTheVersion() {
    when(repository.find(user.id())).thenReturn(Optional.of(user));
    UserView result = service.update(user.id(), "new@example.test", "New Name", 0, context);
    assertEquals(1, result.version());
    assertEquals("new@example.test", result.email());
    verify(repository).update(argThat(updated -> updated.displayName().equals("New Name")), eq(0L));
    verify(audit).record(argThat(event -> event.details().get("version").equals("1")));
  }

  @Test
  void changesStatusAndDoesNotAuditANoop() {
    when(repository.find(user.id())).thenReturn(Optional.of(user));
    assertEquals("LOCKED", service.status(user.id(), "LOCKED", 0, context).status());
    verify(repository).update(any(), eq(0L));
    verify(audit)
        .record(argThat(event -> event.type().value().equals("identity.user.statuschanged")));
    verify(audit)
        .record(
            argThat(
                event ->
                    event.type().value().equals("user.locked")
                        && event.details().get("version").equals("1")));
    clearInvocations(repository, audit);
    assertEquals("ACTIVE", service.status(user.id(), "ACTIVE", 0, context).status());
    verify(repository, never()).update(any(), anyLong());
    verifyNoInteractions(audit);
    assertThrows(
        IllegalArgumentException.class, () -> service.status(user.id(), "UNKNOWN", 0, context));
  }

  @Test
  void replacesPasswordWithoutReturningOrAuditingTheSecret() {
    when(repository.find(user.id())).thenReturn(Optional.of(user));
    when(passwords.hash("Another-safe-password")).thenReturn("new-hash");
    service.password(user.id(), "Another-safe-password", 0, context);
    verify(credentials).replace(user.id(), "new-hash");
    verify(repository).update(any(), eq(0L));
    verify(audit)
        .record(
            argThat(
                event ->
                    event.type().value().equals("identity.user.passwordchanged")
                        && !event.details().toString().contains("hash")));
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"short", "            "})
  void rejectsWeakPasswordsBeforeWritingAnyState(String password) {
    assertThrows(
        IllegalArgumentException.class,
        () -> service.create("a@example.test", "Name", password, context));
    verifyNoInteractions(credentials, repository, passwords, audit);
  }

  @Test
  void rejectsLongPasswordsAndStaleOrInvalidVersions() {
    assertThrows(
        IllegalArgumentException.class,
        () -> service.create("a@example.test", "Name", "a".repeat(129), context));
    when(repository.find(user.id())).thenReturn(Optional.of(user));
    assertThrows(
        ConcurrentUpdateException.class,
        () -> service.update(user.id(), "a@example.test", "Name", 1, context));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.update(user.id(), "a@example.test", "Name", -1, context));
    verify(repository, never()).update(any(), anyLong());
    verifyNoInteractions(audit);
  }

  @Test
  void propagatesAuditFailureSoTheTransactionCanRollback() {
    when(passwords.hash(anyString())).thenReturn("encoded-hash");
    doThrow(new IllegalStateException("audit unavailable")).when(audit).record(any());
    assertThrows(
        IllegalStateException.class,
        () -> service.create("a@example.test", "Name", "Long-test-password", context));
  }
}

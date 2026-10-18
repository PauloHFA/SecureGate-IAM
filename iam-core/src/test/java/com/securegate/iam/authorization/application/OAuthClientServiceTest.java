package com.securegate.iam.authorization.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.securegate.iam.audit.application.port.AuditTrail;
import com.securegate.iam.authorization.application.port.ClientRegistry;
import com.securegate.iam.authorization.domain.MachineClient;
import com.securegate.iam.identity.application.port.PasswordHasher;
import com.securegate.iam.shared.application.ConcurrentUpdateException;
import com.securegate.iam.shared.application.ResourceNotFoundException;
import com.securegate.iam.shared.application.port.OperationContext;
import com.securegate.iam.shared.application.port.UnitOfWork;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OAuthClientServiceTest {
  private final ClientRegistry clients = mock(ClientRegistry.class);
  private final PasswordHasher passwords = mock(PasswordHasher.class);
  private final AuditTrail audit = mock(AuditTrail.class);
  private final OperationContext context = new OperationContext("test-admin", "oauth-test");
  private final ClientView view =
      new ClientView("test-client", "Test", "test-api", Set.of("api:read"), 300);
  private final OAuthClientService service =
      new OAuthClientService(
          clients,
          passwords,
          audit,
          new UnitOfWork() {
            public <T> T execute(Supplier<T> task) {
              return task.get();
            }
          });

  @Test
  void issuesDifferentHighEntropySecretsAndAuditsWithoutTheSecretOrHash() {
    when(passwords.hash(anyString())).thenReturn("synthetic-hash");
    when(clients.create(any(), eq("synthetic-hash"))).thenReturn(view);
    var first = service.create("test-client", "Test", "test-api", Set.of("api:read"), context);
    var second = service.create("test-client", "Test", "test-api", Set.of("api:read"), context);
    assertTrue(first.clientSecret().matches("[a-zA-Z0-9_-]{43}"));
    assertNotEquals(first.clientSecret(), second.clientSecret());
    assertFalse(first.toString().contains(first.clientSecret()));
    verify(passwords).hash(first.clientSecret());
    verify(audit, times(2))
        .record(
            argThat(
                event ->
                    event.type().value().equals("oauth.client.created")
                        && event.context().equals(context)
                        && !event.details().toString().contains("hash")
                        && !event.details().toString().contains("secret")));
    assertEquals(view, first.client());
  }

  @Test
  void readsSafeMetadataAndReportsMissingClients() {
    when(clients.find("test-client")).thenReturn(Optional.of(view));
    assertEquals(view, service.get("test-client"));
    assertThrows(ResourceNotFoundException.class, () -> service.get("missing-client"));
  }

  @Test
  void duplicateRegistrationDoesNotOverwriteTheClientOrAudit() {
    when(clients.find("test-client")).thenReturn(Optional.of(view));
    assertThrows(
        ConcurrentUpdateException.class,
        () -> service.create("test-client", "Test", "test-api", Set.of("api:read"), context));
    verify(clients, never()).create(any(), any());
    verifyNoInteractions(audit);
  }

  @ParameterizedTest
  @ValueSource(strings = {"openid", "offline_access", "*", "api read", "UPPER"})
  void rejectsUnsafeOrInteractiveScopesBeforeWriting(String scope) {
    assertThrows(
        IllegalArgumentException.class,
        () -> service.create("test-client", "Test", "test-api", Set.of(scope), context));
    verifyNoInteractions(clients, passwords, audit);
  }

  @Test
  void validatesClientFieldsAndCopiesScopeSets() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new MachineClient(null, "Name", "api", Set.of("read")));
    assertThrows(
        IllegalArgumentException.class,
        () -> new MachineClient("valid-id", " ", "api", Set.of("read")));
    assertThrows(
        IllegalArgumentException.class,
        () -> new MachineClient("valid-id", "Name", "bad audience", Set.of("read")));
    assertThrows(
        IllegalArgumentException.class,
        () -> new MachineClient("valid-id", "Name", "api", Set.of()));
    var model = new MachineClient("valid-id", " Name ", "api", Set.of("read"));
    assertEquals("Name", model.name());
    assertThrows(UnsupportedOperationException.class, () -> model.scopes().add("write"));
  }
}

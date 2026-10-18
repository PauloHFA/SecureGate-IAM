package com.securegate.iam.policy.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.securegate.iam.access.application.port.EffectiveRoles;
import com.securegate.iam.audit.application.port.AuditTrail;
import com.securegate.iam.identity.application.port.IdentityLookup;
import com.securegate.iam.policy.application.port.PolicyDocuments;
import com.securegate.iam.policy.application.port.PolicyRepository;
import com.securegate.iam.policy.domain.Policy;
import com.securegate.iam.policy.domain.PolicyEffect;
import com.securegate.iam.policy.domain.PolicyRule;
import com.securegate.iam.shared.application.ConcurrentUpdateException;
import com.securegate.iam.shared.application.ResourceNotFoundException;
import com.securegate.iam.shared.application.port.OperationContext;
import com.securegate.iam.shared.application.port.PageRequest;
import com.securegate.iam.shared.application.port.UnitOfWork;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class PolicyServiceTest {
  private final PolicyRepository policies = mock(PolicyRepository.class);
  private final PolicyDocuments documents = mock(PolicyDocuments.class);
  private final IdentityLookup identities = mock(IdentityLookup.class);
  private final EffectiveRoles roles = mock(EffectiveRoles.class);
  private final AuditTrail audit = mock(AuditTrail.class);
  private final OperationContext actor = new OperationContext("admin", "policy-test");
  private final PolicyRule rule =
      new PolicyRule(1, "invoices", "read", PolicyEffect.ALLOW, Set.of("READER"), Set.of(), null);
  private final Policy existing =
      new Policy(UUID.randomUUID(), "invoice-reader", 10, true, 3, rule);
  private final PolicyService service =
      new PolicyService(
          policies,
          documents,
          identities,
          roles,
          audit,
          new UnitOfWork() {
            public <T> T execute(Supplier<T> operation) {
              return operation.get();
            }
          },
          (userId, resource, contextId) ->
              com.securegate.iam.policy.domain.RiskSignals.unknown(java.time.Instant.EPOCH),
          java.time.Clock.fixed(java.time.Instant.EPOCH, java.time.ZoneOffset.UTC));

  @Test
  void createsUpdatesReadsAndListsWithMetadataOnlyAudit() {
    when(documents.decode("{}")).thenReturn(rule);
    when(documents.encode(rule)).thenReturn("{}");
    when(policies.find(existing.id())).thenReturn(Optional.of(existing));
    var created = service.create("new-policy", 1, false, "{}", actor);
    assertEquals(0, created.version());
    assertFalse(created.enabled());
    verify(policies).create(argThat(policy -> policy.id().equals(created.id())));
    var updated = service.update(existing.id(), "changed", 2, false, "{}", 3, actor);
    assertEquals(4, updated.version());
    verify(policies).update(argThat(policy -> policy.version() == 4), eq(3L));
    verify(audit)
        .record(
            argThat(
                event ->
                    event.type().value().equals("policy.updated")
                        && event.context().equals(actor)
                        && event.details().get("version").equals("4")
                        && !event.details().containsKey("document")));
    assertEquals(existing.name(), service.get(existing.id()).name());
    var page = new PageRequest(20, 0);
    when(policies.list(page)).thenReturn(List.of(existing));
    assertEquals(1, service.list(page).size());
  }

  @Test
  void missingAndStalePoliciesCannotBeUpdatedOrAudited() {
    when(documents.decode("{}")).thenReturn(rule);
    assertThrows(ResourceNotFoundException.class, () -> service.get(existing.id()));
    assertThrows(
        ResourceNotFoundException.class,
        () -> service.update(existing.id(), "changed", 1, true, "{}", 3, actor));
    when(policies.find(existing.id())).thenReturn(Optional.of(existing));
    assertThrows(
        ConcurrentUpdateException.class,
        () -> service.update(existing.id(), "changed", 1, true, "{}", 2, actor));
    verify(policies, never()).update(any(), anyLong());
    verifyNoInteractions(audit);
  }

  @Test
  void usesCurrentRolesAndReturnsAppliedPolicyVersions() {
    UUID user = UUID.randomUUID();
    when(identities.isActive(user)).thenReturn(true);
    when(roles.forUser(user)).thenReturn(List.of("READER"));
    when(policies.candidates("invoices", "read", 1001)).thenReturn(List.of(existing));
    var decision = service.evaluate(user, "invoices", "read", Set.of("api:read"));
    assertEquals("ALLOW", decision.effect());
    assertEquals(
        new DecisionView.AppliedPolicy(existing.id(), 3), decision.appliedPolicies().getFirst());
    verifyNoInteractions(audit);
  }

  @Test
  void inactiveIdentityAndTooManyPoliciesFailClosed() {
    UUID user = UUID.randomUUID();
    assertEquals(
        List.of("IDENTITY_INACTIVE"),
        service.evaluate(user, "invoices", "read", Set.of("api:read")).reasons());
    verifyNoInteractions(policies, roles);
    when(identities.isActive(user)).thenReturn(true);
    when(policies.candidates("invoices", "read", 1001))
        .thenReturn(Collections.nCopies(1001, existing));
    assertEquals(
        List.of("POLICY_LIMIT_EXCEEDED"),
        service.evaluate(user, "invoices", "read", Set.of("api:read")).reasons());
    assertThrows(
        IllegalArgumentException.class,
        () -> service.evaluate(null, "invoices", "read", Set.of("api:read")));
  }

  @Test
  void marksSyntheticContextAndNeverTreatsItAsRealEvidence() {
    UUID user = UUID.randomUUID();
    when(identities.isActive(user)).thenReturn(true);
    when(roles.forUser(user)).thenReturn(List.of("READER"));
    when(policies.candidates("invoices", "read", 1001)).thenReturn(List.of(existing));
    var actual = service.evaluate(user, "invoices", "read", Set.of("api:read"));
    assertEquals(100, actual.risk().score());
    assertFalse(actual.simulation());
    var simulated =
        service.simulate(
            user,
            "invoices",
            "read",
            Set.of("api:read"),
            new SimulationSignals("TRUSTED", "TRUSTED", 0, "PUBLIC"));
    assertEquals(0, simulated.risk().score());
    assertTrue(simulated.simulation());
    assertEquals(
        100, service.evaluate(user, "invoices", "read", Set.of("api:read")).risk().score());
    assertThrows(
        IllegalArgumentException.class,
        () -> service.simulate(user, "invoices", "read", Set.of("api:read"), null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SimulationSignals("untrusted", null, null, null).at(java.time.Instant.EPOCH));
    assertEquals(
        100,
        service
            .simulate(
                user,
                "invoices",
                "read",
                Set.of("api:read"),
                new SimulationSignals(null, null, null, null))
            .risk()
            .score());
  }
}

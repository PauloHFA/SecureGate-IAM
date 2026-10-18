package com.securegate.iam.policy.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class PolicyEvaluatorTest {
  private final PolicyEvaluator evaluator = new PolicyEvaluator();
  private final EvaluationContext context =
      new EvaluationContext("invoices", "read", Set.of("READER"), Set.of("api:read", "api:write"));

  private Policy policy(PolicyEffect effect, int priority) {
    return new Policy(
        UUID.randomUUID(),
        "policy-" + priority,
        priority,
        true,
        0,
        new PolicyRule(
            1,
            "invoices",
            "read",
            effect,
            Set.of("READER"),
            effect == PolicyEffect.ALLOW_LIMITED ? Set.of("api:read") : Set.of(),
            effect == PolicyEffect.ALLOW_LIMITED ? 120 : null));
  }

  @ParameterizedTest
  @CsvSource({
    "ALLOW,ALLOW,ALLOW",
    "DENY,ALLOW,DENY",
    "ALLOW,DENY,DENY",
    "ALLOW_WITH_MFA,ALLOW,ALLOW_WITH_MFA",
    "ALLOW,ALLOW_LIMITED,ALLOW_LIMITED",
    "ALLOW_LIMITED,ALLOW_WITH_MFA,ALLOW_WITH_MFA",
    "ALLOW_WITH_MFA,DENY,DENY",
    "ALLOW_LIMITED,DENY,DENY",
    "DENY,DENY,DENY",
    "DENY,ALLOW_WITH_MFA,DENY",
    "DENY,ALLOW_LIMITED,DENY",
    "ALLOW,ALLOW_WITH_MFA,ALLOW_WITH_MFA",
    "ALLOW_WITH_MFA,ALLOW_LIMITED,ALLOW_WITH_MFA",
    "ALLOW_WITH_MFA,ALLOW_WITH_MFA,ALLOW_WITH_MFA",
    "ALLOW_LIMITED,ALLOW,ALLOW_LIMITED",
    "ALLOW_LIMITED,ALLOW_LIMITED,ALLOW_LIMITED"
  })
  void combinesEffectsWithoutLettingPriorityWeakenRestrictions(
      PolicyEffect first, PolicyEffect second, PolicyEffect expected) {
    var high = policy(first, 1000);
    var low = policy(second, 0);
    var forward = evaluator.evaluate(List.of(low, high), context);
    var reversed = evaluator.evaluate(List.of(high, low), context);
    assertEquals(forward, reversed);
    assertEquals(expected, forward.effect());
    assertEquals(high.id(), forward.appliedPolicies().getFirst().id());
    assertEquals(expected == PolicyEffect.ALLOW_WITH_MFA, forward.mfaRequired());
    if (expected == PolicyEffect.DENY) {
      assertTrue(forward.allowedScopes().isEmpty());
      assertEquals(0, forward.maxAccessTokenTtlSeconds());
      assertEquals(List.of("EXPLICIT_DENY"), forward.reasons());
    } else if (first == PolicyEffect.ALLOW_LIMITED || second == PolicyEffect.ALLOW_LIMITED) {
      assertEquals(Set.of("api:read"), forward.allowedScopes());
      assertEquals(120, forward.maxAccessTokenTtlSeconds());
      assertTrue(forward.reasons().contains("LIMITED"));
    } else {
      assertEquals(context.requestedScopes(), forward.allowedScopes());
      assertEquals(300, forward.maxAccessTokenTtlSeconds());
    }
  }

  @Test
  void deniesNoMatchesIncludingDisabledUnrelatedAndWrongRoles() {
    var allow = policy(PolicyEffect.ALLOW, 1);
    var disabled = new Policy(allow.id(), allow.name(), 1, false, 2, allow.document());
    assertEquals(List.of("NO_MATCH"), evaluator.evaluate(List.of(), context).reasons());
    assertEquals(PolicyEffect.DENY, evaluator.evaluate(List.of(disabled), context).effect());
    assertEquals(
        PolicyEffect.DENY,
        evaluator
            .evaluate(
                List.of(allow),
                new EvaluationContext("payments", "read", Set.of("READER"), Set.of("api:read")))
            .effect());
    assertEquals(
        PolicyEffect.DENY,
        evaluator
            .evaluate(
                List.of(allow),
                new EvaluationContext("invoices", "write", Set.of("READER"), Set.of("api:read")))
            .effect());
    assertEquals(
        PolicyEffect.DENY,
        evaluator
            .evaluate(
                List.of(allow),
                new EvaluationContext("invoices", "read", Set.of("OTHER"), Set.of("api:read")))
            .effect());
  }

  @Test
  void intersectsAllLimitsAndFailsClosedWhenIntersectionIsEmpty() {
    var first = policy(PolicyEffect.ALLOW_LIMITED, 0);
    var second =
        new Policy(
            UUID.randomUUID(),
            "second-limit",
            1,
            true,
            7,
            new PolicyRule(
                1, "*", "*", PolicyEffect.ALLOW_LIMITED, Set.of(), Set.of("api:write"), 60));
    var denied = evaluator.evaluate(List.of(first, second), context);
    assertEquals(PolicyEffect.DENY, denied.effect());
    assertEquals(List.of("NO_PERMITTED_SCOPES"), denied.reasons());
    assertEquals(7, denied.appliedPolicies().getFirst().version());
    var compatible =
        new Policy(
            second.id(),
            second.name(),
            1,
            true,
            8,
            new PolicyRule(
                1,
                "*",
                "*",
                PolicyEffect.ALLOW_LIMITED,
                Set.of(),
                Set.of("api:read", "api:write"),
                60));
    var result = evaluator.evaluate(List.of(compatible, first), context);
    assertEquals(Set.of("api:read"), result.allowedScopes());
    assertEquals(60, result.maxAccessTokenTtlSeconds());
    assertThrows(UnsupportedOperationException.class, () -> result.allowedScopes().clear());
    assertThrows(UnsupportedOperationException.class, () -> result.appliedPolicies().clear());
    assertThrows(UnsupportedOperationException.class, () -> result.reasons().clear());
  }

  @Test
  void ordersEqualPriorityByIdAndCopiesRoleAndScopeInputs() {
    var roles = new java.util.HashSet<>(Set.of("READER"));
    var scopes = new java.util.HashSet<>(Set.of("api:read"));
    var request = new EvaluationContext("invoices", "read", roles, scopes);
    var rule = new PolicyRule(1, "*", "*", PolicyEffect.ALLOW, roles, Set.of(), null);
    roles.clear();
    scopes.clear();
    var p1 = new Policy(new UUID(0, 1), "first", 0, true, 0, rule);
    var p2 = new Policy(new UUID(0, 2), "second", 0, true, 0, rule);
    var result = evaluator.evaluate(List.of(p2, p1), request);
    assertEquals(p1.id(), result.appliedPolicies().getFirst().id());
    assertEquals(PolicyEffect.ALLOW, result.effect());
    assertThrows(UnsupportedOperationException.class, () -> rule.rolesAnyOf().clear());
  }

  @ParameterizedTest
  @ValueSource(strings = {"plain", "prefix*", "../x", "UPPER", "", "a b"})
  void rejectsInvalidInputExceptAnExactValidTarget(String target) {
    if (target.equals("plain")) {
      assertDoesNotThrow(() -> new EvaluationContext(target, "read", Set.of(), Set.of("api:read")));
    } else {
      assertThrows(
          IllegalArgumentException.class,
          () -> new EvaluationContext(target, "read", Set.of(), Set.of("api:read")));
    }
  }

  @Test
  void rejectsInvalidDefinitionsAndPreservesVersioning() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new PolicyRule(2, "*", "*", PolicyEffect.ALLOW, Set.of(), Set.of(), null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new PolicyRule(1, "*", "*", null, Set.of(), Set.of(), null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new PolicyRule(1, "*", "*", PolicyEffect.ALLOW, Set.of("reader"), Set.of(), null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new PolicyRule(1, "*", "*", PolicyEffect.ALLOW_LIMITED, Set.of(), Set.of(), 60));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PolicyRule(
                1, "*", "*", PolicyEffect.ALLOW_LIMITED, Set.of(), Set.of("api:read"), 29));
    assertThrows(
        IllegalArgumentException.class,
        () -> new PolicyRule(1, "*", "*", PolicyEffect.ALLOW, Set.of(), Set.of("api:read"), null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new PolicyRule(1, "*", "*", PolicyEffect.ALLOW, Set.of(), Set.of(), 60));
    assertThrows(
        IllegalArgumentException.class,
        () -> new EvaluationContext("*", "read", Set.of(), Set.of("api:read")));
    assertThrows(
        IllegalArgumentException.class,
        () -> new EvaluationContext("invoices", "*", Set.of(), Set.of("api:read")));
    assertThrows(
        IllegalArgumentException.class,
        () -> new EvaluationContext("invoices", "read", Set.of(), Set.of()));
    var original = policy(PolicyEffect.ALLOW, 0);
    var updated = original.replace("updated", 1000, false, original.document());
    assertEquals(1, updated.version());
    assertFalse(updated.enabled());
    assertEquals(original.id(), updated.id());
    assertThrows(
        IllegalArgumentException.class,
        () -> original.replace("bad name", 0, true, original.document()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Policy(original.id(), "valid", -1, true, 0, original.document()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Policy(original.id(), "valid", 0, true, Long.MAX_VALUE, original.document()));
  }
}

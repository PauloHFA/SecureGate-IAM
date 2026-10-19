package com.securegate.iam.policy.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class AbacRiskTest {
  private static final Instant NOW = Instant.parse("2026-10-18T18:00:00Z");
  private final RiskCalculator calculator = new RiskCalculator();

  private RiskSignals safe() {
    return new RiskSignals(
        RiskSignals.Trust.TRUSTED,
        RiskSignals.Trust.TRUSTED,
        0,
        RiskSignals.Sensitivity.PUBLIC,
        NOW);
  }

  private AttributeCondition condition(
      AttributeCondition.Attribute attribute, AttributeCondition.Operator operator, String value) {
    return new AttributeCondition(attribute, operator, List.of(value));
  }

  @ParameterizedTest
  @CsvSource({"0,0", "1,10", "2,10", "3,20", "4,20", "5,30", "1000,30"})
  void scoresRecentFailuresWithBoundedWeights(int failures, int expected) {
    var signals =
        new RiskSignals(
            RiskSignals.Trust.TRUSTED,
            RiskSignals.Trust.TRUSTED,
            failures,
            RiskSignals.Sensitivity.PUBLIC,
            NOW);
    var first = calculator.assess(signals, NOW);
    assertEquals(expected, first.score());
    assertEquals(first, calculator.assess(signals, NOW));
    assertTrue(first.missingSignals().isEmpty());
  }

  @ParameterizedTest
  @CsvSource({"PUBLIC,0", "INTERNAL,5", "CONFIDENTIAL,15", "RESTRICTED,25", "UNKNOWN,25"})
  void scoresSensitivity(RiskSignals.Sensitivity sensitivity, int expected) {
    assertEquals(
        expected,
        calculator
            .assess(
                new RiskSignals(
                    RiskSignals.Trust.TRUSTED, RiskSignals.Trust.TRUSTED, 0, sensitivity, NOW),
                NOW)
            .score());
  }

  @ParameterizedTest
  @CsvSource({
    "0,LOW",
    "29,LOW",
    "30,MEDIUM",
    "59,MEDIUM",
    "60,HIGH",
    "79,HIGH",
    "80,CRITICAL",
    "100,CRITICAL"
  })
  void classifiesRiskAtExactBoundaries(int score, RiskAssessment.Level level) {
    assertEquals(level, RiskAssessment.Level.forScore(score));
  }

  @ParameterizedTest
  @ValueSource(longs = {-301, 6})
  void staleOrFutureEvidenceBecomesUnknown(long offset) {
    var signals =
        new RiskSignals(
            RiskSignals.Trust.TRUSTED,
            RiskSignals.Trust.TRUSTED,
            0,
            RiskSignals.Sensitivity.PUBLIC,
            NOW.plusSeconds(offset));
    var risk = calculator.assess(signals, NOW);
    assertEquals(100, risk.score());
    assertEquals(4, risk.missingSignals().size());
    assertTrue(risk.reasons().contains("INVALID_SIGNAL_TIME"));
    assertEquals(RiskSignals.Trust.UNKNOWN, risk.signals().deviceTrust());
  }

  @Test
  void handlesUnknownEvidenceConservativelyAndCopiesReasons() {
    var missing = calculator.assess(RiskSignals.unknown(NOW), NOW);
    assertEquals(100, missing.score());
    assertEquals(RiskAssessment.Level.CRITICAL, missing.level());
    assertEquals(
        Set.of("DEVICE_TRUST", "NETWORK_TRUST", "FAILED_LOGIN_ATTEMPTS", "RESOURCE_SENSITIVITY"),
        Set.copyOf(missing.missingSignals()));
    assertEquals(100, calculator.assess(null, NOW).score());
    var untrusted =
        calculator.assess(
            new RiskSignals(
                RiskSignals.Trust.UNTRUSTED,
                RiskSignals.Trust.UNTRUSTED,
                5,
                RiskSignals.Sensitivity.RESTRICTED,
                NOW),
            NOW);
    assertEquals(100, untrusted.score());
    assertTrue(untrusted.missingSignals().isEmpty());
    assertEquals(0, calculator.assess(safe(), NOW).score());
    assertEquals(
        0,
        calculator
            .assess(
                new RiskSignals(
                    RiskSignals.Trust.TRUSTED,
                    RiskSignals.Trust.TRUSTED,
                    0,
                    RiskSignals.Sensitivity.PUBLIC,
                    NOW.minusSeconds(300)),
                NOW)
            .score());
    assertEquals(
        0,
        calculator
            .assess(
                new RiskSignals(
                    RiskSignals.Trust.TRUSTED,
                    RiskSignals.Trust.TRUSTED,
                    0,
                    RiskSignals.Sensitivity.PUBLIC,
                    NOW.plusSeconds(5)),
                NOW)
            .score());
    assertThrows(UnsupportedOperationException.class, () -> missing.missingSignals().clear());
    assertThrows(UnsupportedOperationException.class, () -> missing.reasons().clear());
    assertThrows(IllegalArgumentException.class, () -> RiskAssessment.Level.forScore(-1));
    assertThrows(IllegalArgumentException.class, () -> RiskAssessment.Level.forScore(101));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RiskSignals(
                null, RiskSignals.Trust.TRUSTED, 0, RiskSignals.Sensitivity.PUBLIC, NOW));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RiskSignals(
                RiskSignals.Trust.TRUSTED,
                RiskSignals.Trust.TRUSTED,
                -1,
                RiskSignals.Sensitivity.PUBLIC,
                NOW));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RiskSignals(
                RiskSignals.Trust.TRUSTED,
                RiskSignals.Trust.TRUSTED,
                1001,
                RiskSignals.Sensitivity.PUBLIC,
                NOW));
  }

  @ParameterizedTest
  @CsvSource({
    "DEVICE_TRUST,EQ,TRUSTED,MATCH",
    "NETWORK_TRUST,IN,UNTRUSTED,NO_MATCH",
    "FAILED_LOGIN_ATTEMPTS,LTE,0,MATCH",
    "FAILED_LOGIN_ATTEMPTS,GTE,1,NO_MATCH",
    "RESOURCE_SENSITIVITY,EQ,PUBLIC,MATCH",
    "RISK_SCORE,LTE,29,MATCH",
    "RISK_LEVEL,IN,LOW,MATCH"
  })
  void evaluatesTypedConditions(
      AttributeCondition.Attribute attribute,
      AttributeCondition.Operator operator,
      String value,
      AttributeCondition.Match expected) {
    assertEquals(
        expected, condition(attribute, operator, value).evaluate(calculator.assess(safe(), NOW)));
  }

  @ParameterizedTest
  @CsvSource({
    "DEVICE_TRUST,TRUSTED",
    "NETWORK_TRUST,TRUSTED",
    "FAILED_LOGIN_ATTEMPTS,0",
    "RESOURCE_SENSITIVITY,PUBLIC"
  })
  void unknownAttributesAreIndeterminate(AttributeCondition.Attribute attribute, String value) {
    assertEquals(
        AttributeCondition.Match.INDETERMINATE,
        condition(attribute, AttributeCondition.Operator.EQ, value)
            .evaluate(calculator.assess(null, NOW)));
  }

  @ParameterizedTest
  @CsvSource({
    "RISK_SCORE,IN,0",
    "RISK_SCORE,EQ,-1",
    "RISK_SCORE,EQ,101",
    "RISK_SCORE,EQ,01",
    "FAILED_LOGIN_ATTEMPTS,EQ,1001",
    "DEVICE_TRUST,GTE,TRUSTED",
    "DEVICE_TRUST,EQ,UNKNOWN",
    "NETWORK_TRUST,EQ,true",
    "RESOURCE_SENSITIVITY,EQ,SECRET",
    "RISK_LEVEL,EQ,low"
  })
  void rejectsUnknownLiteralsAndUnsupportedOperators(
      AttributeCondition.Attribute attribute, AttributeCondition.Operator operator, String value) {
    assertThrows(IllegalArgumentException.class, () -> condition(attribute, operator, value));
  }

  private Policy policy(PolicyEffect effect, List<AttributeCondition> conditions) {
    return new Policy(
        UUID.randomUUID(),
        "policy-test",
        1,
        true,
        0,
        new PolicyRule(2, "invoices", "read", effect, Set.of(), Set.of(), null, conditions));
  }

  @Test
  void indeterminatePolicyCannotBeSkippedToAllowAnotherRule() {
    var allow = policy(PolicyEffect.ALLOW, List.of());
    var conditionalDeny =
        policy(
            PolicyEffect.DENY,
            List.of(
                condition(
                    AttributeCondition.Attribute.DEVICE_TRUST,
                    AttributeCondition.Operator.EQ,
                    "UNTRUSTED")));
    var unknown =
        new EvaluationContext(
            "invoices", "read", Set.of(), Set.of("api:read"), calculator.assess(null, NOW));
    var evaluator = new PolicyEvaluator();
    var denied = evaluator.evaluate(List.of(allow, conditionalDeny), unknown);
    assertEquals(PolicyEffect.DENY, denied.effect());
    assertEquals(List.of("INDETERMINATE_ATTRIBUTES"), denied.reasons());
    assertEquals(2, denied.appliedPolicies().size());
    assertEquals(
        PolicyEffect.ALLOW,
        evaluator
            .evaluate(
                List.of(allow, conditionalDeny),
                new EvaluationContext(
                    "invoices",
                    "read",
                    Set.of(),
                    Set.of("api:read"),
                    calculator.assess(safe(), NOW)))
            .effect());
    var explicit = policy(PolicyEffect.DENY, List.of());
    assertEquals(
        List.of("EXPLICIT_DENY"),
        evaluator.evaluate(List.of(allow, conditionalDeny, explicit), unknown).reasons());
    var impossible =
        policy(
            PolicyEffect.DENY,
            List.of(
                condition(
                    AttributeCondition.Attribute.DEVICE_TRUST,
                    AttributeCondition.Operator.EQ,
                    "TRUSTED"),
                condition(
                    AttributeCondition.Attribute.RISK_SCORE,
                    AttributeCondition.Operator.LTE,
                    "0")));
    assertEquals(
        PolicyEffect.ALLOW, evaluator.evaluate(List.of(allow, impossible), unknown).effect());
  }

  @Test
  void preservesV1AndBoundsGrammarWithoutMutableConditions() {
    var values = new java.util.ArrayList<>(List.of("TRUSTED"));
    var condition =
        new AttributeCondition(
            AttributeCondition.Attribute.DEVICE_TRUST, AttributeCondition.Operator.IN, values);
    values.clear();
    var conditions = new java.util.ArrayList<>(List.of(condition));
    var policy = policy(PolicyEffect.ALLOW, conditions);
    conditions.clear();
    assertEquals(1, policy.document().conditions().size());
    assertThrows(UnsupportedOperationException.class, () -> condition.values().clear());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PolicyRule(
                1, "*", "*", PolicyEffect.ALLOW, Set.of(), Set.of(), null, List.of(condition)));
    assertThrows(
        IllegalArgumentException.class,
        () -> policy(PolicyEffect.ALLOW, java.util.Collections.nCopies(11, condition)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AttributeCondition(
                AttributeCondition.Attribute.DEVICE_TRUST,
                AttributeCondition.Operator.EQ,
                List.of("TRUSTED", "UNTRUSTED")));
    assertThrows(
        IllegalArgumentException.class,
        () -> new AttributeCondition(null, AttributeCondition.Operator.EQ, List.of("TRUSTED")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AttributeCondition(
                AttributeCondition.Attribute.DEVICE_TRUST,
                AttributeCondition.Operator.EQ,
                List.of()));
  }
}

package com.securegate.iam.policy.domain;

import java.util.List;

/** Closed, flat ALL grammar; values are enum literals or bounded decimal integers. */
public record AttributeCondition(Attribute attribute, Operator operator, List<String> values) {
  public enum Attribute {
    DEVICE_TRUST,
    NETWORK_TRUST,
    FAILED_LOGIN_ATTEMPTS,
    RESOURCE_SENSITIVITY,
    RISK_SCORE,
    RISK_LEVEL
  }

  public enum Operator {
    EQ,
    IN,
    GTE,
    LTE
  }

  public enum Match {
    MATCH,
    NO_MATCH,
    INDETERMINATE
  }

  public AttributeCondition {
    if (attribute == null
        || operator == null
        || values == null
        || values.isEmpty()
        || values.size() > 10
        || values.stream().anyMatch(value -> value == null || value.length() > 32)
        || (operator != Operator.IN && values.size() != 1)) {
      throw new IllegalArgumentException("Invalid ABAC condition");
    }
    boolean numeric =
        attribute == Attribute.RISK_SCORE || attribute == Attribute.FAILED_LOGIN_ATTEMPTS;
    if (numeric && operator == Operator.IN
        || !numeric && operator != Operator.EQ && operator != Operator.IN) {
      throw new IllegalArgumentException("Unsupported ABAC operator");
    }
    for (String value : values) {
      if (numeric) {
        int maximum = attribute == Attribute.RISK_SCORE ? 100 : 1000;
        if (!value.matches("0|[1-9][0-9]{0,3}") || Integer.parseInt(value) > maximum) {
          throw new IllegalArgumentException("Invalid ABAC numeric value");
        }
      } else {
        boolean valid =
            switch (attribute) {
              case DEVICE_TRUST, NETWORK_TRUST -> List.of("TRUSTED", "UNTRUSTED").contains(value);
              case RESOURCE_SENSITIVITY ->
                  List.of("PUBLIC", "INTERNAL", "CONFIDENTIAL", "RESTRICTED").contains(value);
              case RISK_LEVEL -> List.of("LOW", "MEDIUM", "HIGH", "CRITICAL").contains(value);
              default -> false;
            };
        if (!valid) {
          throw new IllegalArgumentException("Invalid ABAC enum value");
        }
      }
    }
    values = List.copyOf(values);
  }

  public Match evaluate(RiskAssessment risk) {
    var signals = risk.signals();
    String actual =
        switch (attribute) {
          case DEVICE_TRUST ->
              signals.deviceTrust() == RiskSignals.Trust.UNKNOWN
                  ? null
                  : signals.deviceTrust().name();
          case NETWORK_TRUST ->
              signals.networkTrust() == RiskSignals.Trust.UNKNOWN
                  ? null
                  : signals.networkTrust().name();
          case FAILED_LOGIN_ATTEMPTS ->
              signals.failedLoginAttempts() == null
                  ? null
                  : signals.failedLoginAttempts().toString();
          case RESOURCE_SENSITIVITY ->
              signals.resourceSensitivity() == RiskSignals.Sensitivity.UNKNOWN
                  ? null
                  : signals.resourceSensitivity().name();
          case RISK_SCORE -> Integer.toString(risk.score());
          case RISK_LEVEL -> risk.level().name();
        };
    if (actual == null) {
      return Match.INDETERMINATE;
    }
    boolean matched =
        switch (operator) {
          case EQ -> actual.equals(values.getFirst());
          case IN -> values.contains(actual);
          case GTE -> Integer.parseInt(actual) >= Integer.parseInt(values.getFirst());
          case LTE -> Integer.parseInt(actual) <= Integer.parseInt(values.getFirst());
        };
    return matched ? Match.MATCH : Match.NO_MATCH;
  }
}

package com.securegate.iam.policy.domain;

import java.util.List;
import java.util.Set;

/** Versioned, closed grammar: exact target (or *), and an optional any-role condition. */
public record PolicyRule(
    int schemaVersion,
    String resource,
    String action,
    PolicyEffect effect,
    Set<String> rolesAnyOf,
    Set<String> allowedScopes,
    Integer maxAccessTokenTtlSeconds,
    List<AttributeCondition> conditions) {
  public PolicyRule(
      int schemaVersion,
      String resource,
      String action,
      PolicyEffect effect,
      Set<String> rolesAnyOf,
      Set<String> allowedScopes,
      Integer maxAccessTokenTtlSeconds) {
    this(
        schemaVersion,
        resource,
        action,
        effect,
        rolesAnyOf,
        allowedScopes,
        maxAccessTokenTtlSeconds,
        List.of());
  }

  public PolicyRule {
    if ((schemaVersion != 1 && schemaVersion != 2)
        || conditions == null
        || conditions.size() > 10
        || conditions.stream().anyMatch(java.util.Objects::isNull)
        || (schemaVersion == 1 && !conditions.isEmpty())
        || !target(resource)
        || !target(action)
        || effect == null
        || rolesAnyOf == null
        || rolesAnyOf.size() > 20
        || rolesAnyOf.stream()
            .anyMatch(role -> role == null || !role.matches("[A-Z][A-Z0-9_]{0,63}"))) {
      throw new IllegalArgumentException("Invalid policy rule");
    }
    validateScopes(allowedScopes, true);
    if (effect == PolicyEffect.ALLOW_LIMITED) {
      if (allowedScopes.isEmpty()
          || maxAccessTokenTtlSeconds == null
          || maxAccessTokenTtlSeconds < 30
          || maxAccessTokenTtlSeconds > 300) {
        throw new IllegalArgumentException(
            "Limited policies require scopes and a TTL from 30 to 300 seconds");
      }
    } else if (!allowedScopes.isEmpty() || maxAccessTokenTtlSeconds != null) {
      throw new IllegalArgumentException("Restrictions require ALLOW_LIMITED");
    }
    rolesAnyOf = Set.copyOf(rolesAnyOf);
    allowedScopes = Set.copyOf(allowedScopes);
    conditions = List.copyOf(conditions);
  }

  public boolean matches(EvaluationContext context) {
    return (resource.equals("*") || resource.equals(context.resource()))
        && (action.equals("*") || action.equals(context.action()))
        && (rolesAnyOf.isEmpty() || rolesAnyOf.stream().anyMatch(context.roles()::contains));
  }

  public AttributeCondition.Match match(EvaluationContext context) {
    if (!matches(context)) {
      return AttributeCondition.Match.NO_MATCH;
    }
    boolean missing = false;
    for (var condition : conditions) {
      var match = condition.evaluate(context.risk());
      if (match == AttributeCondition.Match.NO_MATCH) {
        return match;
      }
      missing |= match == AttributeCondition.Match.INDETERMINATE;
    }
    return missing ? AttributeCondition.Match.INDETERMINATE : AttributeCondition.Match.MATCH;
  }

  static boolean target(String value) {
    return "*".equals(value) || (value != null && value.matches("[a-z][a-z0-9:_./-]{0,127}"));
  }

  static void validateScopes(Set<String> scopes, boolean allowEmpty) {
    if (scopes == null
        || (!allowEmpty && scopes.isEmpty())
        || scopes.size() > 10
        || scopes.stream()
            .anyMatch(scope -> scope == null || !scope.matches("[a-z][a-z0-9:._-]{0,63}"))) {
      throw new IllegalArgumentException("Invalid policy scopes");
    }
  }
}

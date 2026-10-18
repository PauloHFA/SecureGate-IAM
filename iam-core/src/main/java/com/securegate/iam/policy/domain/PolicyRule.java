package com.securegate.iam.policy.domain;

import java.util.Set;

/** Versioned, closed grammar: exact target (or *), and an optional any-role condition. */
public record PolicyRule(
    int schemaVersion,
    String resource,
    String action,
    PolicyEffect effect,
    Set<String> rolesAnyOf,
    Set<String> allowedScopes,
    Integer maxAccessTokenTtlSeconds) {
  public PolicyRule {
    if (schemaVersion != 1
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
  }

  public boolean matches(EvaluationContext context) {
    return (resource.equals("*") || resource.equals(context.resource()))
        && (action.equals("*") || action.equals(context.action()))
        && (rolesAnyOf.isEmpty() || rolesAnyOf.stream().anyMatch(context.roles()::contains));
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

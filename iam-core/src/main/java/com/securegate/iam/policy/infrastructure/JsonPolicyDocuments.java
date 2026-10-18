package com.securegate.iam.policy.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securegate.iam.policy.application.port.PolicyDocuments;
import com.securegate.iam.policy.domain.PolicyRule;

public final class JsonPolicyDocuments implements PolicyDocuments {
  private final ObjectMapper json;

  public JsonPolicyDocuments(ObjectMapper json) {
    this.json =
        json.copy()
            .enable(
                DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS,
                DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
  }

  @Override
  public PolicyRule decode(String document) {
    if (document == null || document.length() > 8192) {
      throw new IllegalArgumentException("Invalid policy document size");
    }
    try {
      var tree = json.readTree(document);
      if (tree == null || !tree.isObject() || !tree.path("schemaVersion").isInt()) {
        throw new IllegalArgumentException("Policy document is required");
      }
      int version = tree.path("schemaVersion").intValue();
      if (version == 1) {
        if (tree.has("conditions")) {
          throw new IllegalArgumentException("ABAC conditions require schema v2");
        }
        ((com.fasterxml.jackson.databind.node.ObjectNode) tree).putArray("conditions");
      }
      if (version == 2) {
        if (!tree.path("conditions").isArray()) {
          throw new IllegalArgumentException("Conditions are required in schema v2");
        }
        for (var condition : tree.path("conditions")) {
          if (!condition.path("values").isArray()) {
            throw new IllegalArgumentException("Condition values must be an array");
          }
          for (var value : condition.path("values")) {
            if (!value.isTextual()) {
              throw new IllegalArgumentException("Condition literals must be strings");
            }
          }
        }
      }
      return json.treeToValue(tree, PolicyRule.class);
    } catch (JsonProcessingException failure) {
      throw new IllegalArgumentException("Invalid policy document");
    }
  }

  @Override
  public String encode(PolicyRule rule) {
    try {
      var tree = json.valueToTree(rule);
      if (rule.schemaVersion() == 1) {
        ((com.fasterxml.jackson.databind.node.ObjectNode) tree).remove("conditions");
      }
      return json.writeValueAsString(tree);
    } catch (JsonProcessingException failure) {
      throw new IllegalStateException("Policy serialization failed");
    }
  }
}

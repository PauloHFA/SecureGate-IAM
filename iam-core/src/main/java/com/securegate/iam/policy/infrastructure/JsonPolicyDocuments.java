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
      var rule = json.readValue(document, PolicyRule.class);
      if (rule == null) {
        throw new IllegalArgumentException("Policy document is required");
      }
      return rule;
    } catch (JsonProcessingException failure) {
      throw new IllegalArgumentException("Invalid policy document");
    }
  }

  @Override
  public String encode(PolicyRule rule) {
    try {
      return json.writeValueAsString(rule);
    } catch (JsonProcessingException failure) {
      throw new IllegalStateException("Policy serialization failed");
    }
  }
}

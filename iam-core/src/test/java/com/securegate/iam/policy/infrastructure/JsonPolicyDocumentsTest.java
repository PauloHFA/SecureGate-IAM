package com.securegate.iam.policy.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JsonPolicyDocumentsTest {
  private final JsonPolicyDocuments documents = new JsonPolicyDocuments(new ObjectMapper());
  private static final String V1 =
      """
      {"schemaVersion":1,"resource":"invoices","action":"read","effect":"ALLOW","rolesAnyOf":[],"allowedScopes":[],"maxAccessTokenTtlSeconds":null}
      """;

  @Test
  void roundTripsBothVersionsAndKeepsLegacyDocumentShape() {
    var v1 = documents.decode(V1);
    assertFalse(documents.encode(v1).contains("conditions"));
    assertEquals(v1, documents.decode(documents.encode(v1)));
    String v2 =
        V1.replace("\"schemaVersion\":1", "\"schemaVersion\":2")
            .replace(
                "\"rolesAnyOf\":[]",
                "\"rolesAnyOf\":[],\"conditions\":[{\"attribute\":\"RISK_SCORE\",\"operator\":\"LTE\",\"values\":[\"29\"]}]");
    var rule = documents.decode(v2);
    assertEquals(1, rule.conditions().size());
    assertEquals(rule, documents.decode(documents.encode(rule)));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "legacy-conditions",
        "missing-conditions",
        "unknown-attribute",
        "unknown-operator",
        "unknown-field",
        "coercion",
        "numeric-value",
        "trailing",
        "null"
      })
  void rejectsInvalidGrammar(String scenario) {
    String v2 =
        V1.replace("\"schemaVersion\":1", "\"schemaVersion\":2")
            .replace(
                "\"rolesAnyOf\":[]",
                "\"rolesAnyOf\":[],\"conditions\":[{\"attribute\":\"RISK_SCORE\",\"operator\":\"LTE\",\"values\":[\"29\"]}]");
    String invalid =
        switch (scenario) {
          case "legacy-conditions" -> v2.replace("\"schemaVersion\":2", "\"schemaVersion\":1");
          case "missing-conditions" -> V1.replace("\"schemaVersion\":1", "\"schemaVersion\":2");
          case "unknown-attribute" -> v2.replace("RISK_SCORE", "request.headers.authorization");
          case "unknown-operator" -> v2.replace("LTE", "SCRIPT");
          case "unknown-field" ->
              v2.replace("\"operator\":\"LTE\"", "\"operator\":\"LTE\",\"script\":\"unsafe\"");
          case "coercion" -> v2.replace("\"schemaVersion\":2", "\"schemaVersion\":\"2\"");
          case "numeric-value" -> v2.replace("[\"29\"]", "[29]");
          case "trailing" -> v2 + " {}";
          default -> "null";
        };
    assertThrows(IllegalArgumentException.class, () -> documents.decode(invalid));
  }
}

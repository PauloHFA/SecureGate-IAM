package com.securegate.e2e;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securegate.events.SecurityProjectionKeys;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Contract and governance test for Stage 12B: verifies the end-to-end demonstration journey
 * artifacts, Bruno and Postman API collections, demonstration scripts, and edge revocation
 * mechanics.
 */
class DemoJourneyIT {

  private final ObjectMapper json = new ObjectMapper();

  @Test
  void validatesBrunoCollectionStructureAndRequests() throws Exception {
    Path brunoDir = Path.of("../docs/colecoes/bruno").toAbsolutePath();
    assertTrue(Files.isDirectory(brunoDir), "docs/colecoes/bruno must be a directory");

    Path brunoJson = brunoDir.resolve("bruno.json");
    assertTrue(Files.isRegularFile(brunoJson), "bruno.json must exist");
    JsonNode node = json.readTree(brunoJson.toFile());
    assertEquals("SecureGate IAM", node.get("name").asText());
    assertEquals("collection", node.get("type").asText());

    Path envFile = brunoDir.resolve("environments/Local.bru");
    assertTrue(Files.isRegularFile(envFile), "environments/Local.bru must exist");
    String envContent = Files.readString(envFile);
    assertTrue(envContent.contains("baseUrlCore"), "Must define baseUrlCore");
    assertTrue(envContent.contains("baseUrlGateway"), "Must define baseUrlGateway");
    assertTrue(envContent.contains("bootstrapToken"), "Must define bootstrapToken");

    // Verify key folders and request files exist
    assertTrue(
        Files.isRegularFile(brunoDir.resolve("01-health-checks/core-health.bru")),
        "core-health.bru must exist");
    assertTrue(
        Files.isRegularFile(brunoDir.resolve("01-health-checks/gateway-health.bru")),
        "gateway-health.bru must exist");
    assertTrue(
        Files.isRegularFile(brunoDir.resolve("02-oauth2-metadata/openid-configuration.bru")),
        "openid-configuration.bru must exist");
    assertTrue(
        Files.isRegularFile(brunoDir.resolve("02-oauth2-metadata/jwks.bru")),
        "jwks.bru must exist");
    assertTrue(
        Files.isRegularFile(brunoDir.resolve("03-identity-admin/create-user.bru")),
        "create-user.bru must exist");
    assertTrue(
        Files.isRegularFile(brunoDir.resolve("04-demonstration-journey/step1-issue-token.bru")),
        "step1-issue-token.bru must exist");
    assertTrue(
        Files.isRegularFile(
            brunoDir.resolve("04-demonstration-journey/step2-access-resource-gateway-success.bru")),
        "step2-access-resource-gateway-success.bru must exist");
    assertTrue(
        Files.isRegularFile(
            brunoDir.resolve("04-demonstration-journey/step3-simulate-suspicious-lock.bru")),
        "step3-simulate-suspicious-lock.bru must exist");
    assertTrue(
        Files.isRegularFile(
            brunoDir.resolve("04-demonstration-journey/step4-access-resource-gateway-denied.bru")),
        "step4-access-resource-gateway-denied.bru must exist");
    assertTrue(
        Files.isRegularFile(brunoDir.resolve("05-pam/request-pam.bru")),
        "request-pam.bru must exist");
  }

  @Test
  void validatesPostmanCollectionAndEnvironmentJson() throws Exception {
    Path postmanCollection =
        Path.of("../docs/colecoes/securegate-iam.postman_collection.json").toAbsolutePath();
    assertTrue(Files.isRegularFile(postmanCollection), "Postman collection file must exist");

    JsonNode colNode = json.readTree(postmanCollection.toFile());
    assertTrue(
        colNode.get("info").get("schema").asText().contains("v2.1.0"),
        "Must adhere to Postman v2.1.0 schema");
    assertEquals("SecureGate IAM - API Collection", colNode.get("info").get("name").asText());
    assertEquals(5, colNode.get("item").size(), "Must contain 5 top-level category folders");

    Path postmanEnv =
        Path.of("../docs/colecoes/securegate-iam.postman_environment.json").toAbsolutePath();
    assertTrue(Files.isRegularFile(postmanEnv), "Postman environment file must exist");

    JsonNode envNode = json.readTree(postmanEnv.toFile());
    assertEquals("SecureGate Local", envNode.get("name").asText());
    JsonNode values = envNode.get("values");
    assertTrue(values.isArray(), "Values must be an array");
    boolean hasGateway = false;
    for (JsonNode val : values) {
      if ("baseUrlGateway".equals(val.get("key").asText())) {
        hasGateway = true;
        break;
      }
    }
    assertTrue(hasGateway, "Postman environment must declare baseUrlGateway");
  }

  @Test
  void validatesDemonstrationScripts() throws Exception {
    Path ps1 = Path.of("../infra/scripts/Test-DemonstrationJourney.ps1").toAbsolutePath();
    assertTrue(Files.isRegularFile(ps1), "Test-DemonstrationJourney.ps1 must exist");
    String ps1Content = Files.readString(ps1);
    assertTrue(ps1Content.contains("8082"), "Must target gateway port 8082");
    assertTrue(ps1Content.contains("Compute-Sha256Hex"), "Must compute SHA-256 for Redis keys");
    assertTrue(ps1Content.contains("securegate:{revocation}:jti:"), "Must use canonical key wire");

    Path sh = Path.of("../infra/scripts/demonstrate-journey.sh").toAbsolutePath();
    assertTrue(Files.isRegularFile(sh), "demonstrate-journey.sh must exist");
    String shContent = Files.readString(sh);
    assertTrue(shContent.contains("GATEWAY_URI"), "Must parameterize GATEWAY_URI");
    assertTrue(shContent.contains("TokenRevocationGatewayFilter"), "Must document filter logic");
  }

  @Test
  void validatesCanonicalProjectionKeyFormatForRevocation() {
    String testJti = UUID.randomUUID().toString();
    String key = SecurityProjectionKeys.key("revocation", "jti", testJti);

    assertNotNull(key);
    assertTrue(key.startsWith("securegate:{revocation}:jti:"));
    assertEquals(28 + 64, key.length(), "Prefix (28) + SHA-256 hex (64) = 92 chars");

    String testSid = UUID.randomUUID().toString();
    String sidKey = SecurityProjectionKeys.key("revocation", "sid", testSid);
    assertTrue(sidKey.startsWith("securegate:{revocation}:sid:"));

    String testSub = UUID.randomUUID().toString();
    String subKey = SecurityProjectionKeys.key("block-policy", "blocked:account", testSub + ":1");
    assertTrue(subKey.startsWith("securegate:{block-policy}:blocked:account:"));
  }
}

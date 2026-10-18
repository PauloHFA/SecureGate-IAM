package com.securegate.iam.authorization.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securegate.iam.authorization.application.SigningUnavailableException;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class VaultTransitSigningKeysTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final KeyPair KEY = generateKey();
  @TempDir Path directory;
  private HttpServer server;
  private ExecutorService executor;
  private VaultTransitSigningKeys keys;
  private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
  private final AtomicReference<String> mode = new AtomicReference<>("normal");
  private final AtomicInteger logins = new AtomicInteger();
  private final AtomicInteger reads = new AtomicInteger();
  private final AtomicInteger lease = new AtomicInteger(3600);
  private final CountDownLatch signing = new CountDownLatch(1);
  private final CountDownLatch release = new CountDownLatch(1);

  @BeforeEach
  void setup() throws Exception {
    Files.writeString(directory.resolve("role"), "synthetic-role");
    Files.writeString(directory.resolve("secret"), "synthetic-secret");
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    executor = Executors.newCachedThreadPool();
    server.setExecutor(executor);
    server.createContext(
        "/v1/",
        exchange -> {
          try {
            String path = exchange.getRequestURI().getPath();
            byte[] body = exchange.getRequestBody().readAllBytes();
            String scenario = mode.get();
            Object response;
            int status = 200;
            if (scenario.equals("slow")) {
              Thread.sleep(1500);
            }
            if (path.endsWith("login")) {
              logins.incrementAndGet();
              response =
                  Map.of(
                      "auth",
                      Map.of(
                          "client_token",
                          "synthetic-token-" + logins.get(),
                          "lease_duration",
                          lease.get()));
            } else if (path.contains("/keys/")) {
              reads.incrementAndGet();
              String pem =
                  "-----BEGIN PUBLIC KEY-----\n"
                      + Base64.getEncoder().encodeToString(KEY.getPublic().getEncoded())
                      + "\n-----END PUBLIC KEY-----";
              response =
                  Map.of(
                      "data",
                      Map.of(
                          "type",
                          scenario.equals("wrong-type") ? "rsa-2048" : "rsa-3072",
                          "exportable",
                          scenario.equals("exportable"),
                          "allow_plaintext_backup",
                          scenario.equals("backup"),
                          "latest_version",
                          scenario.equals("missing-version") ? 2 : 1,
                          "keys",
                          Map.of(
                              "1",
                              Map.of("public_key", scenario.equals("bad-pem") ? "invalid" : pem))));
            } else {
              var request = JSON.readTree(body);
              if (scenario.equals("blocked")) {
                signing.countDown();
                release.await(3, TimeUnit.SECONDS);
              }
              if (scenario.equals("forbidden")) {
                status = 403;
                response = Map.of("errors", new String[] {"synthetic-secret never forward this"});
              } else {
                assertEquals("pkcs1v15", request.path("signature_algorithm").asText());
                assertEquals("sha2-256", request.path("hash_algorithm").asText());
                assertFalse(request.path("prehashed").asBoolean());
                assertEquals(1, request.path("key_version").asInt());
                Signature signer = Signature.getInstance("SHA256withRSA");
                signer.initSign(KEY.getPrivate());
                signer.update(Base64.getDecoder().decode(request.path("input").asText()));
                String signature = Base64.getEncoder().encodeToString(signer.sign());
                response =
                    Map.of(
                        "data",
                        Map.of(
                            "signature",
                            scenario.equals("bad-base64")
                                ? "vault:v1:!"
                                : scenario.equals("short-signature")
                                    ? "vault:v1:AA=="
                                    : (scenario.equals("wrong-version") ? "vault:v2:" : "vault:v1:")
                                        + signature));
              }
            }
            byte[] output = JSON.writeValueAsBytes(response);
            exchange.sendResponseHeaders(status, output.length);
            exchange.getResponseBody().write(output);
          } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
          } catch (Exception exception) {
            // Test clients may deliberately time out or cancel before the server writes.
          } finally {
            exchange.close();
          }
        });
    server.start();
    keys = adapter(Duration.ofMillis(500), 2);
  }

  @AfterEach
  void cleanup() {
    release.countDown();
    keys.close();
    server.stop(0);
    executor.shutdownNow();
    metrics.close();
  }

  @Test
  void cachesPublicKeysAndAppRoleSessionAndRecordsBoundedMetrics() {
    var ring = keys.publicKeys();
    assertEquals("securegate-jwt-v1", ring.active().id());
    assertSame(ring, keys.publicKeys());
    assertEquals(1, reads.get());
    assertEquals(1, logins.get());
    keys.sign(ring.active(), new byte[] {1, 2, 3});
    keys.sign(ring.active(), new byte[] {4, 5, 6});
    assertEquals(1, logins.get());
    assertEquals(
        2,
        metrics
            .get("iam.transit.requests")
            .tags("operation", "sign", "outcome", "success")
            .timer()
            .count());
  }

  @ParameterizedTest
  @ValueSource(strings = {"wrong-type", "exportable", "backup", "missing-version", "bad-pem"})
  void rejectsUnsafeOrMalformedKeyMetadata(String scenario) {
    mode.set(scenario);
    var failure = assertThrows(SigningUnavailableException.class, keys::publicKeys);
    assertEquals("Remote signing is unavailable.", failure.getMessage());
    assertNull(failure.getCause());
  }

  @ParameterizedTest
  @ValueSource(strings = {"wrong-version", "bad-base64", "short-signature", "forbidden"})
  void rejectsMalformedOrDeniedSignaturesWithoutLeakingRemoteErrors(String scenario) {
    var key = keys.publicKeys().active();
    mode.set(scenario);
    var failure =
        assertThrows(SigningUnavailableException.class, () -> keys.sign(key, new byte[] {1}));
    assertEquals("Remote signing is unavailable.", failure.getMessage());
    assertNull(failure.getCause());
  }

  @Test
  void invalidatedSessionIsReauthenticatedOnTheNextOperationWithoutRetryingTheFailedSign() {
    var key = keys.publicKeys().active();
    mode.set("forbidden");
    assertThrows(SigningUnavailableException.class, () -> keys.sign(key, new byte[] {1}));
    assertEquals(1, logins.get());
    mode.set("normal");
    assertEquals(384, keys.sign(key, new byte[] {1}).length);
    assertEquals(2, logins.get());
    assertEquals(
        1,
        metrics
            .get("iam.transit.requests")
            .tags("operation", "sign", "outcome", "failure")
            .timer()
            .count());
  }

  @Test
  void expiredSessionRequiresANewLogin() throws Exception {
    lease.set(2);
    var key = keys.publicKeys().active();
    Thread.sleep(1100);
    assertEquals(384, keys.sign(key, new byte[] {1}).length);
    assertEquals(2, logins.get());
  }

  @Test
  void missingCredentialFileFailsWithoutFallbackOrSensitiveExceptionDetails() throws Exception {
    Files.delete(directory.resolve("secret"));
    var failure = assertThrows(SigningUnavailableException.class, keys::publicKeys);
    assertEquals("Remote signing is unavailable.", failure.getMessage());
    assertNull(failure.getCause());
    assertEquals(0, logins.get());
  }

  @Test
  void remoteTimeoutFailsWithinABoundedTime() {
    mode.set("slow");
    assertTimeoutPreemptively(
        Duration.ofSeconds(3),
        () -> assertThrows(SigningUnavailableException.class, keys::publicKeys));
    assertEquals(
        1,
        metrics
            .get("iam.transit.requests")
            .tags("operation", "login", "outcome", "failure")
            .timer()
            .count());
  }

  @Test
  void preservesThreadInterruption() {
    Thread.currentThread().interrupt();
    try {
      assertThrows(SigningUnavailableException.class, keys::publicKeys);
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void rejectsExcessConcurrencyWithoutQueuingMoreVaultRequests() throws Exception {
    keys.close();
    keys = adapter(Duration.ofSeconds(2), 1);
    var key = keys.publicKeys().active();
    mode.set("blocked");
    try (var callers = Executors.newSingleThreadExecutor()) {
      var first = callers.submit(() -> keys.sign(key, new byte[] {1}));
      assertTrue(signing.await(2, TimeUnit.SECONDS));
      try {
        assertThrows(SigningUnavailableException.class, () -> keys.sign(key, new byte[] {2}));
        assertEquals(
            1, metrics.get("iam.transit.rejected").tag("operation", "sign").counter().count());
      } finally {
        release.countDown();
      }
      assertEquals(384, first.get(2, TimeUnit.SECONDS).length);
    }
  }

  @Test
  void rejectsInvalidSigningInputsBeforeNetworkAccess() {
    var key = keys.publicKeys().active();
    assertThrows(IllegalArgumentException.class, () -> keys.sign(key, new byte[0]));
    assertThrows(IllegalArgumentException.class, () -> keys.sign(key, new byte[32769]));
    assertThrows(IllegalArgumentException.class, () -> keys.sign(null, new byte[] {1}));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "http://vault.example",
        "http://127.0.0.1:8200/path",
        "https://user:secret@vault.example",
        "https://vault.example?token=secret",
        "ftp://127.0.0.1",
        "https://vault.example:99999"
      })
  void rejectsUnsafeAddresses(String address) {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new VaultTransitSettings(
                true,
                URI.create(address),
                directory.resolve("role"),
                directory.resolve("secret"),
                "securegate-jwt",
                Duration.ofSeconds(1),
                Duration.ofSeconds(30),
                8));
  }

  private VaultTransitSigningKeys adapter(Duration timeout, int concurrency) {
    var settings =
        new VaultTransitSettings(
            true,
            URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
            directory.resolve("role"),
            directory.resolve("secret"),
            "securegate-jwt",
            timeout,
            Duration.ofSeconds(30),
            concurrency);
    return new VaultTransitSigningKeys(settings, JSON, metrics);
  }

  private static KeyPair generateKey() {
    try {
      var generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(3072);
      return generator.generateKeyPair();
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }
}

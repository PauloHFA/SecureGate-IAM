package com.securegate.e2e;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.dockerjava.api.model.AccessMode;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Volume;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;

/** Disposable IAM -> outbox -> RabbitMQ -> enforcer/audit -> Tempo/Grafana journey. */
class TracingJourneyIT {
  private static final HttpClient HTTP = PropagationEnvironment.HTTP;

  @Test
  void exportsRealBusinessChainAndContinuesThroughCollectorOutage() throws Exception {
    String password = UUID.randomUUID().toString();
    try (var network = Network.newNetwork();
        var tempo =
            new GenericContainer<>(
                    TracingBackendIT.image("docker-compose.tracing.yml", "grafana/tempo:"))
                .withNetwork(network)
                .withNetworkAliases("tempo")
                .withExposedPorts(3200, 4318)
                .withCommand("-target=all", "-config.file=/etc/tempo.yml")
                .withCreateContainerCmdModifier(
                    cmd ->
                        cmd.getHostConfig()
                            .withBinds(
                                new Bind(
                                    TracingBackendIT.ROOT.resolve("tracing/tempo.yml").toString(),
                                    new Volume("/etc/tempo.yml"),
                                    AccessMode.ro)))
                .waitingFor(Wait.forHttp("/ready").forPort(3200))
                .withStartupTimeout(Duration.ofSeconds(120));
        var grafana =
            new GenericContainer<>(TracingBackendIT.image("docker-compose.yml", "grafana/grafana:"))
                .withNetwork(network)
                .withExposedPorts(3000)
                .withEnv("GF_SECURITY_ADMIN_USER", "admin")
                .withEnv("GF_SECURITY_ADMIN_PASSWORD", password)
                .withEnv("GF_AUTH_ANONYMOUS_ENABLED", "false")
                .withEnv("GF_USERS_ALLOW_SIGN_UP", "false")
                .withEnv("GF_ANALYTICS_REPORTING_ENABLED", "false")
                .withEnv("GF_ANALYTICS_CHECK_FOR_UPDATES", "false")
                .withEnv("GF_PLUGINS_CHECK_FOR_UPDATES", "false")
                .withEnv("GF_PLUGINS_PREINSTALL_DISABLED", "true")
                .withCreateContainerCmdModifier(
                    cmd ->
                        cmd.getHostConfig()
                            .withBinds(
                                new Bind(
                                    TracingBackendIT.ROOT
                                        .resolve("grafana/provisioning")
                                        .toString(),
                                    new Volume("/etc/grafana/provisioning"),
                                    AccessMode.ro),
                                new Bind(
                                    TracingBackendIT.ROOT
                                        .resolve("tracing/grafana-tempo.yml")
                                        .toString(),
                                    new Volume("/etc/grafana/provisioning/datasources/tempo.yml"),
                                    AccessMode.ro)))
                .waitingFor(Wait.forHttp("/api/health"))
                .withStartupTimeout(Duration.ofSeconds(90))) {
      tempo.start();
      grafana.start();
      URI backend = URI.create("http://127.0.0.1:" + tempo.getMappedPort(3200));
      URI receiver = URI.create("http://127.0.0.1:" + tempo.getMappedPort(4318) + "/v1/traces");
      URI ui = URI.create("http://127.0.0.1:" + grafana.getMappedPort(3000));
      String auth =
          "Basic "
              + Base64.getEncoder()
                  .encodeToString(("admin:" + password).getBytes(StandardCharsets.UTF_8));
      var settings =
          Map.of(
              "SECUREGATE_TRACING_EXPORT_ENABLED",
              "true",
              "SECUREGATE_TRACING_EXPORT_ENDPOINT",
              receiver.toString());
      try (var environment = new PropagationEnvironment(settings)) {
        environment.start();
        environment.enableAuditJourney();
        var ids = new ArrayList<String>();
        ids.add(lockUser(environment));
        String firstTrace = traceId(environment, ids.getFirst());
        var first = awaitChain(backend, firstTrace);
        assertTrue(
            names(first).containsAll(List.of("http.server", "outbox.publish", "security.consume")));
        assertTrue(first.toString().contains("iam-core"));
        assertTrue(first.toString().contains("policy-enforcer"));
        assertFalse(first.toString().contains(password));
        URI proxy =
            ui.resolve("/api/datasources/proxy/uid/securegate-tempo/api/traces/" + firstTrace);
        assertEquals(401, TracingBackendIT.get(HTTP, proxy, null).statusCode());
        assertTrue(
            names(TracingBackendIT.awaitTrace(HTTP, proxy, auth)).contains("security.consume"));

        tempo.getDockerClient().pauseContainerCmd(tempo.getContainerId()).exec();
        try {
          ids.add(lockUser(environment));
          assertTrue(
              projected(environment, ids.getLast()), "Business must continue during export outage");
        } finally {
          tempo.getDockerClient().unpauseContainerCmd(tempo.getContainerId()).exec();
        }
        TracingBackendIT.awaitReady(HTTP, backend.resolve("/ready"));
        // Bounded joint collection, one real event every two seconds. This is not a load benchmark.
        long started = System.nanoTime();
        for (int i = 0; i < 10; i++) {
          ids.add(lockUser(environment));
          Thread.sleep(2000);
        }
        assertTrue(Duration.ofNanos(System.nanoTime() - started).toSeconds() >= 20);
        for (String id : ids.subList(2, ids.size())) {
          assertTrue(projected(environment, id));
          assertTrue(
              names(awaitChain(backend, traceId(environment, id)))
                  .containsAll(List.of("http.server", "outbox.publish", "security.consume")));
        }
        // The audit batch is a separate root linked to producer traces.
        PropagationEnvironment.await(
            "audit batch queryable with producer links",
            Duration.ofSeconds(45),
            () -> {
              URI search =
                  backend.resolve("/api/search?q=%7Bname%3D%22audit.consume.batch%22%7D&limit=20");
              var result = TracingBackendIT.get(HTTP, search, null);
              if (result.statusCode() != 200) return false;
              for (JsonNode item :
                  PropagationEnvironment.JSON.readTree(result.body()).path("traces")) {
                String id = item.path("traceID").asText();
                if (id.isBlank()) continue;
                JsonNode batch = trace(backend, id);
                if (names(batch).contains("audit.consume.batch")
                    && batch.findValues("links").stream()
                        .anyMatch(value -> value.isArray() && !value.isEmpty())) return true;
              }
              return false;
            });
      }
    }
  }

  static String lockUser(PropagationEnvironment env) throws Exception {
    String email = "trace-" + UUID.randomUUID() + "@example.test";
    var created =
        HTTP.send(
            HttpRequest.newBuilder(env.base.resolve("/api/admin/users"))
                .header("Authorization", "Bearer " + env.admin)
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        PropagationEnvironment.JSON.writeValueAsString(
                            Map.of(
                                "email",
                                email,
                                "displayName",
                                "Synthetic trace",
                                "password",
                                UUID.randomUUID().toString()))))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(201, created.statusCode());
    String id = PropagationEnvironment.JSON.readTree(created.body()).path("id").asText();
    var locked =
        HTTP.send(
            HttpRequest.newBuilder(env.base.resolve("/api/admin/users/" + id + "/status"))
                .header("Authorization", "Bearer " + env.admin)
                .header("Content-Type", "application/json")
                .method(
                    "PATCH",
                    HttpRequest.BodyPublishers.ofString("{\"status\":\"LOCKED\",\"version\":0}"))
                .build(),
            HttpResponse.BodyHandlers.discarding());
    assertEquals(200, locked.statusCode());
    PropagationEnvironment.await(
        "user.locked projection", Duration.ofSeconds(30), () -> projected(env, id));
    return id;
  }

  static boolean projected(PropagationEnvironment env, String id) {
    String value =
        env.redis
            .opsForValue()
            .get(com.securegate.events.SecurityProjectionKeys.key("block-policy", "identity", id));
    return value != null && value.endsWith("|LOCKED");
  }

  static String traceId(PropagationEnvironment env, String userId) {
    var rows =
        env.jdbc.queryForList(
            "SELECT trace_parent FROM iam_audit.outbox_events WHERE event_type='user.locked' AND payload->'data'->>'userId'=?",
            String.class,
            userId);
    assertEquals(1, rows.size());
    String parent = rows.getFirst();
    assertNotNull(parent);
    return parent.substring(3, 35);
  }

  private static JsonNode trace(URI backend, String id) throws Exception {
    return TracingBackendIT.awaitTrace(HTTP, backend.resolve("/api/traces/" + id), null);
  }

  static JsonNode awaitChain(URI backend, String id) throws Exception {
    final JsonNode[] found = new JsonNode[1];
    PropagationEnvironment.await(
        "IAM/outbox/enforcer trace",
        Duration.ofSeconds(45),
        () -> {
          var response = TracingBackendIT.get(HTTP, backend.resolve("/api/traces/" + id), null);
          if (response.statusCode() != 200) return false;
          found[0] = PropagationEnvironment.JSON.readTree(response.body());
          return names(found[0])
              .containsAll(List.of("http.server", "outbox.publish", "security.consume"));
        });
    return found[0];
  }

  static List<String> names(JsonNode trace) {
    return trace.findValuesAsText("name");
  }
}

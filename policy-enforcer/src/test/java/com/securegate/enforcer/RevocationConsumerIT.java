package com.securegate.enforcer;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.ConnectionFactory;
import com.securegate.enforcer.application.RevocationHandler;
import com.securegate.enforcer.application.port.RevocationStore;
import com.securegate.enforcer.domain.Revocation;
import com.securegate.enforcer.infrastructure.RabbitRevocationWorker;
import com.securegate.enforcer.infrastructure.RedisRevocationStore;
import com.securegate.enforcer.infrastructure.RevocationMessageHandler;
import com.securegate.events.SecurityEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest
class RevocationConsumerIT {
  private static final String PASSWORD = UUID.randomUUID().toString();

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7.4.11-alpine"))
          .withCommand("redis-server", "--requirepass", PASSWORD)
          .withExposedPorts(6379);

  @Container
  static final GenericContainer<?> RABBIT =
      new GenericContainer<>(DockerImageName.parse("rabbitmq:4.3.6-management-alpine"))
          .withEnv("RABBITMQ_DEFAULT_USER", "consumer_test")
          .withEnv("RABBITMQ_DEFAULT_PASS", PASSWORD)
          .withEnv("RABBITMQ_DEFAULT_VHOST", "securegate")
          .withExposedPorts(5672)
          .waitingFor(Wait.forListeningPort())
          .withStartupTimeout(Duration.ofSeconds(120));

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    registry.add("spring.data.redis.password", () -> PASSWORD);
  }

  @Autowired ObjectMapper json;
  @Autowired StringRedisTemplate redis;
  @Autowired ApplicationContext context;
  private RedisRevocationStore store;
  private SimpleMeterRegistry meters;
  private com.securegate.enforcer.infrastructure.RedisBlockPolicyStore blockStore;

  private RabbitRevocationWorker blockWorker(
      com.securegate.enforcer.application.port.BlockPolicyStore target) {
    var handler =
        new com.securegate.enforcer.infrastructure.BlockPolicyMessageHandler(
            json, new com.securegate.enforcer.application.BlockPolicyHandler(target));
    return new RabbitRevocationWorker(
        factory(),
        json,
        handler::handle,
        meters,
        20,
        100,
        RabbitRevocationWorker.BLOCK_POLICY_QUEUE);
  }

  private SecurityEvent blockEvent(
      String type, String resource, java.util.Map<String, String> data) {
    return BlockPolicyEffectTest.event(type, resource, data, Instant.now());
  }

  private String blockKey(String kind, String subject) {
    return com.securegate.enforcer.infrastructure.RedisBlockPolicyStore.key(kind, subject);
  }

  private int blockCount(String suffix) throws Exception {
    try (var connection = factory().newConnection();
        var channel = connection.createChannel()) {
      return channel
          .queueDeclarePassive(RabbitRevocationWorker.BLOCK_POLICY_QUEUE + suffix)
          .getMessageCount();
    }
  }

  private ConnectionFactory factory() {
    var factory = new ConnectionFactory();
    factory.setHost(RABBIT.getHost());
    factory.setPort(RABBIT.getMappedPort(5672));
    factory.setUsername("consumer_test");
    factory.setPassword(PASSWORD);
    factory.setVirtualHost("securegate");
    factory.setAutomaticRecoveryEnabled(false);
    factory.setConnectionTimeout(2000);
    factory.setHandshakeTimeout(2000);
    factory.setChannelRpcTimeout(2000);
    factory.useNio();
    return factory;
  }

  private RabbitRevocationWorker worker(RevocationStore target) {
    return new RabbitRevocationWorker(
        factory(),
        json,
        new RevocationMessageHandler(json, new RevocationHandler(target)),
        meters,
        20,
        100);
  }

  @BeforeEach
  void prepare() throws Exception {
    redis.execute(
        (org.springframework.data.redis.core.RedisCallback<Void>)
            connection -> {
              connection.serverCommands().flushDb();
              return null;
            });
    store = new RedisRevocationStore(redis);
    meters = new SimpleMeterRegistry();
    blockStore =
        new com.securegate.enforcer.infrastructure.RedisBlockPolicyStore(
            redis, 300, 300, 300, 2, 3);
    try (var worker = blockWorker(blockStore)) {
      worker.runOnce();
    }
    try (var worker = worker(store)) {
      worker.runOnce();
    }
    try (var connection = factory().newConnection();
        var channel = connection.createChannel()) {
      channel.queuePurge(RabbitRevocationWorker.QUEUE);
      channel.queuePurge(RabbitRevocationWorker.QUEUE + ".dlq");
      channel.queuePurge(RabbitRevocationWorker.BLOCK_POLICY_QUEUE);
      channel.queuePurge(RabbitRevocationWorker.BLOCK_POLICY_QUEUE + ".dlq");
    }
  }

  private void publish(SecurityEvent event) throws Exception {
    try (var connection = factory().newConnection();
        var channel = connection.createChannel()) {
      channel.confirmSelect();
      channel.basicPublish(
          "security.events",
          event.eventType(),
          TestEvents.properties(event),
          json.writeValueAsBytes(event));
      assertTrue(channel.waitForConfirms(2000));
    }
  }

  private int count(String suffix) throws Exception {
    try (var connection = factory().newConnection();
        var channel = connection.createChannel()) {
      return channel.queueDeclarePassive(RabbitRevocationWorker.QUEUE + suffix).getMessageCount();
    }
  }

  private SecurityEvent event() {
    return TestEvents.token(
        UUID.randomUUID(),
        "ACCESS_TOKEN",
        UUID.randomUUID().toString(),
        Instant.now().plusSeconds(120));
  }

  private String key(SecurityEvent event) {
    var revocation = Revocation.from(event);
    return RedisRevocationStore.key(revocation.kind(), revocation.target());
  }

  @Test
  void validMessageIsAcknowledgedOnlyAfterItsRedisEffect() throws Exception {
    var event = event();
    publish(event);
    assertNull(redis.opsForValue().get(key(event)));
    try (var worker = worker(store)) {
      worker.runOnce();
    }
    assertEquals(0, count(""));
    assertNotNull(redis.opsForValue().get(key(event)));
    assertTrue(redis.getExpire(key(event), TimeUnit.SECONDS) > 120);
    assertEquals(
        1, meters.get("iam.enforcer.revocation.messages").tag("result", "ACK").counter().count());
  }

  @Test
  void failureBeforeEffectLeavesMessageForRecovery() throws Exception {
    var event = event();
    publish(event);
    try (var worker =
        worker(
            (revocation, fingerprint) -> {
              throw new IllegalStateException("synthetic Redis outage");
            })) {
      worker.runOnce();
    }
    assertEquals(1, count(""));
    assertNull(redis.opsForValue().get(key(event)));
    assertFalse(redis.hasKey("securegate:{revocation}:event:" + event.eventId()));
    try (var worker = worker(store)) {
      worker.runOnce();
    }
    assertEquals(0, count(""));
    assertNotNull(redis.opsForValue().get(key(event)));
  }

  @Test
  void realRedisTimeoutKeepsMessageAndRecoversWithoutDiscard() throws Exception {
    var event = event();
    publish(event);
    assertEquals(
        0,
        REDIS
            .execInContainer("redis-cli", "-a", PASSWORD, "CLIENT", "PAUSE", "3000", "ALL")
            .getExitCode());
    try (var worker = worker(store)) {
      worker.runOnce();
    }
    assertEquals(1, count(""));
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    try (var worker = worker(store)) {
      while (count("") > 0 && System.nanoTime() < deadline) {
        worker.runOnce();
        Thread.sleep(50);
      }
    }
    assertEquals(0, count(""));
    assertNotNull(redis.opsForValue().get(key(event)));
  }

  @Test
  void redeliveryAfterEffectBeforeAckIsIdempotentAndRecreatesMissingProjection() throws Exception {
    var event = event();
    var revocation = Revocation.from(event);
    String fingerprint = RedisRevocationStore.fingerprint(json.writeValueAsBytes(event));
    assertEquals(RevocationStore.Result.APPLIED, store.apply(revocation, fingerprint));
    publish(event);
    publish(event);
    try (var first = worker(store);
        var second = worker(store)) {
      CompletableFuture.allOf(
              CompletableFuture.runAsync(first::runOnce),
              CompletableFuture.runAsync(second::runOnce))
          .get(10, TimeUnit.SECONDS);
    }
    assertEquals(0, count(""));
    assertEquals(Long.toString(revocation.expiresAtMillis()), redis.opsForValue().get(key(event)));
    redis.delete(key(event));
    assertEquals(RevocationStore.Result.DUPLICATE, store.apply(revocation, fingerprint));
    assertNotNull(redis.opsForValue().get(key(event)));
  }

  @Test
  void sameEventIdWithDifferentBytesGoesToDlqWithoutApplyingSecondTarget() throws Exception {
    var original = event();
    publish(original);
    try (var worker = worker(store)) {
      worker.runOnce();
    }
    var conflicting =
        TestEvents.token(
            original.eventId(),
            "ACCESS_TOKEN",
            UUID.randomUUID().toString(),
            Instant.now().plusSeconds(60));
    publish(conflicting);
    try (var worker = worker(store)) {
      worker.runOnce();
    }
    assertEquals(1, count(".dlq"));
    assertNull(redis.opsForValue().get(key(conflicting)));
    assertNotNull(redis.opsForValue().get(key(original)));
  }

  @Test
  void malformedHeadersAndUnsupportedSchemaGoToDlq() throws Exception {
    var event = event();
    try (var connection = factory().newConnection();
        var channel = connection.createChannel()) {
      channel.confirmSelect();
      channel.basicPublish(
          "security.events",
          event.eventType(),
          TestEvents.properties(event).builder().messageId("mismatch").build(),
          json.writeValueAsBytes(event));
      channel.basicPublish(
          "security.events",
          event.eventType(),
          TestEvents.properties(event),
          "{\"schemaVersion\":2}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
      assertTrue(channel.waitForConfirms(2000));
    }
    try (var worker = worker(store)) {
      worker.runOnce();
    }
    assertEquals(2, count(".dlq"));
    assertEquals(0, count(""));
    assertNull(redis.opsForValue().get(key(event)));
  }

  @Test
  void olderRevocationsCannotShortenExpiryAndSessionTombstoneHasNoInventedTtl() throws Exception {
    var id = UUID.randomUUID().toString();
    var latest =
        TestEvents.token(UUID.randomUUID(), "AUTHORIZATION", id, Instant.now().plusSeconds(600));
    var earlier =
        TestEvents.token(UUID.randomUUID(), "AUTHORIZATION", id, Instant.now().plusSeconds(60));
    publish(latest);
    publish(earlier);
    var session = TestEvents.session(UUID.randomUUID());
    publish(session);
    try (var worker = worker(store)) {
      worker.runOnce();
    }
    assertEquals(
        Long.toString(Revocation.from(latest).expiresAtMillis()),
        redis.opsForValue().get(key(latest)));
    assertEquals(-1L, redis.getExpire(key(session), TimeUnit.MILLISECONDS));
    assertEquals(0, count(""));
  }

  @Test
  void expiredEffectIsNotResurrectedAndWrongTypeNeverRecordsCompletion() throws Exception {
    var target = UUID.randomUUID().toString();
    var id = UUID.randomUUID();
    var shortLived =
        new Revocation(id, Revocation.Kind.JTI, target, System.currentTimeMillis() + 1000);
    var fingerprint =
        RedisRevocationStore.fingerprint(
            "synthetic".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    assertEquals(RevocationStore.Result.APPLIED, store.apply(shortLived, fingerprint));
    String key = RedisRevocationStore.key(Revocation.Kind.JTI, target);
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (Boolean.TRUE.equals(redis.hasKey(key)) && System.nanoTime() < deadline) {
      Thread.sleep(25);
    }
    assertFalse(redis.hasKey(key));
    assertEquals(RevocationStore.Result.DUPLICATE, store.apply(shortLived, fingerprint));
    assertFalse(redis.hasKey(key));
    var broken =
        new Revocation(
            UUID.randomUUID(),
            Revocation.Kind.JTI,
            target,
            Instant.now().plusSeconds(60).toEpochMilli());
    redis.opsForList().leftPush(key, "synthetic");
    assertThrows(RuntimeException.class, () -> store.apply(broken, fingerprint));
    assertFalse(redis.hasKey("securegate:{revocation}:event:" + broken.eventId()));
  }

  @Test
  void workerReconnectsAfterActualBrokerRestart() throws Exception {
    var event = event();
    publish(event);
    try (var worker = worker(store)) {
      assertEquals(0, RABBIT.execInContainer("rabbitmqctl", "stop_app").getExitCode());
      try {
        worker.runOnce();
        assertNull(redis.opsForValue().get(key(event)));
      } finally {
        assertEquals(0, RABBIT.execInContainer("rabbitmqctl", "start_app").getExitCode());
      }
      worker.runOnce();
      assertNotNull(redis.opsForValue().get(key(event)));
      assertEquals(0, count(""));
    }
  }

  @Test
  void disabledByDefaultAndOptInSpringContextStartsAndClosesWorker() throws Exception {
    assertTrue(context.getBeansOfType(RabbitRevocationWorker.class).isEmpty());
    assertTrue(context.getBeansOfType(io.micrometer.tracing.Tracer.class).isEmpty());
    assertTrue(
        context.getBeansOfType(io.opentelemetry.sdk.trace.SdkTracerProvider.class).isEmpty());
    var event = event();
    publish(event);
    String user = UUID.randomUUID().toString();
    publish(
        blockEvent(
            "identity.user.created", user, java.util.Map.of("status", "ACTIVE", "version", "0")));
    try (var enabled =
        new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
      enabled.setParent(context);
      enabled
          .getEnvironment()
          .getPropertySources()
          .addFirst(
              new org.springframework.core.env.MapPropertySource(
                  "consumer-test",
                  java.util.Map.of(
                      "enforcer.consumer.enabled",
                      true,
                      "enforcer.consumer.host",
                      RABBIT.getHost(),
                      "enforcer.consumer.port",
                      RABBIT.getMappedPort(5672),
                      "enforcer.consumer.virtual-host",
                      "securegate",
                      "enforcer.consumer.username",
                      "consumer_test",
                      "enforcer.consumer.password",
                      PASSWORD,
                      "enforcer.consumer.poll-millis",
                      100,
                      "management.tracing.enabled",
                      true,
                      "management.tracing.sampling.probability",
                      1)));
      enabled.register(
          com.securegate.enforcer.bootstrap.ConsumerConfiguration.class,
          com.securegate.enforcer.bootstrap.ConsumerTracingConfiguration.class);
      enabled.refresh();
      assertEquals(1, enabled.getBeansOfType(io.micrometer.tracing.Tracer.class).size());
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (!Boolean.TRUE.equals(redis.hasKey(key(event))) && System.nanoTime() < deadline) {
        Thread.sleep(25);
      }
      assertNotNull(redis.opsForValue().get(key(event)));
      deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (!Boolean.TRUE.equals(redis.hasKey(blockKey("identity", user)))
          && System.nanoTime() < deadline) {
        Thread.sleep(25);
      }
      assertNotNull(redis.opsForValue().get(blockKey("identity", user)));
    }
  }

  @Test
  void identityVersionsRemainExactBeyondDoublePrecisionAndOldLocksCannotUndoUnlocks()
      throws Exception {
    String id = UUID.randomUUID().toString();
    publish(
        blockEvent(
            "identity.user.statuschanged",
            id,
            java.util.Map.of("status", "ACTIVE", "version", "9007199254740993")));
    publish(
        blockEvent(
            "user.locked",
            id,
            java.util.Map.of(
                "userId",
                id,
                "version",
                "9007199254740992",
                "status",
                "LOCKED",
                "reason",
                "ADMINISTRATIVE")));
    try (var worker = blockWorker(blockStore)) {
      worker.runOnce();
    }
    assertEquals("0009007199254740993|ACTIVE", redis.opsForValue().get(blockKey("identity", id)));
    assertEquals(-1L, redis.getExpire(blockKey("identity", id), TimeUnit.MILLISECONDS));
    publish(
        blockEvent(
            "identity.user.statuschanged",
            id,
            java.util.Map.of("status", "LOCKED", "version", "9007199254740993")));
    try (var worker = blockWorker(blockStore)) {
      worker.runOnce();
    }
    assertEquals(1, blockCount(".dlq"));
    assertTrue(redis.opsForValue().get(blockKey("identity", id)).endsWith("|ACTIVE"));
  }

  @Test
  void policyVersionsPreserveDisabledNewerStateAndRejectContradictorySameVersion()
      throws Exception {
    String id = UUID.randomUUID().toString();
    java.util.function.BiFunction<String, String, SecurityEvent> change =
        (version, enabled) ->
            blockEvent(
                "policy.changed",
                id,
                java.util.Map.of(
                    "policyId",
                    id,
                    "version",
                    version,
                    "enabled",
                    enabled,
                    "operation",
                    "UPDATED",
                    "resource",
                    "api:test",
                    "action",
                    "read"));
    publish(change.apply("10", "false"));
    publish(change.apply("9", "true"));
    try (var worker = blockWorker(blockStore)) {
      worker.runOnce();
    }
    assertTrue(
        redis.opsForValue().get(blockKey("policy", id)).startsWith("0000000000000000010|false|"));
    publish(change.apply("10", "true"));
    try (var worker = blockWorker(blockStore)) {
      worker.runOnce();
    }
    assertEquals(1, blockCount(".dlq"));
    assertEquals(-1L, redis.getExpire(blockKey("policy", id), TimeUnit.MILLISECONDS));
  }

  private SecurityEvent failure(
      String user, String version, String attempt, String ip, Instant time) {
    var data = new java.util.HashMap<String, String>();
    data.put("attemptId", attempt);
    data.put("reason", "CREDENTIALS_OR_ACCOUNT_REJECTED");
    if (user != null) {
      data.put("userId", user);
      data.put("version", version);
    }
    if (ip != null) {
      data.put("remoteAddress", ip);
    }
    return BlockPolicyEffectTest.event("auth.login.failed", "login", data, time);
  }

  @Test
  void attemptIdAndEventIdDedupPreventDoubleCountingAndCountersAreBounded() throws Exception {
    String user = UUID.randomUUID().toString();
    String attempt = UUID.randomUUID().toString();
    String ip = "203.0.113.10";
    var first = failure(user, "1", attempt, ip, Instant.now());
    publish(first);
    publish(first);
    publish(failure(user, "1", attempt, ip, first.occurredAt()));
    try (var worker = blockWorker(blockStore)) {
      worker.runOnce();
    }
    assertEquals(1L, redis.opsForZSet().zCard(blockKey("attempts:account", user + ":1")));
    assertFalse(redis.hasKey(blockKey("blocked:account", user + ":1")));
    for (int i = 0; i < 8; i++) {
      publish(failure(user, "1", UUID.randomUUID().toString(), ip, Instant.now()));
    }
    try (var worker = blockWorker(blockStore)) {
      worker.runOnce();
    }
    assertEquals(2L, redis.opsForZSet().zCard(blockKey("attempts:account", user + ":1")));
    assertEquals(3L, redis.opsForZSet().zCard(blockKey("attempts:ip", ip)));
    assertTrue(redis.hasKey(blockKey("blocked:account", user + ":1")));
    assertTrue(redis.hasKey(blockKey("blocked:ip", ip)));
    publish(failure(user, "2", UUID.randomUUID().toString(), null, Instant.now()));
    try (var worker = blockWorker(blockStore)) {
      worker.runOnce();
    }
    assertFalse(redis.hasKey(blockKey("blocked:account", user + ":2")));
    assertEquals(0, blockCount(""));
  }

  @Test
  void unknownAccountsContributeOnlyObservedIpAndIpv6LiteralsShareCanonicalCounter()
      throws Exception {
    for (String ip : java.util.List.of("2001:db8::1", "2001:0db8:0:0:0:0:0:1", "2001:db8:0:0::1")) {
      publish(failure(null, null, UUID.randomUUID().toString(), ip, Instant.now()));
    }
    try (var worker = blockWorker(blockStore)) {
      worker.runOnce();
    }
    String ip = com.securegate.events.SecurityEventData.numericAddress("2001:db8::1");
    assertEquals(3L, redis.opsForZSet().zCard(blockKey("attempts:ip", ip)));
    assertTrue(redis.hasKey(blockKey("blocked:ip", ip)));
    assertFalse(redis.hasKey(blockKey("attempts:account", "")));
  }

  @Test
  void expiredSignalsAndDelayedReplaysDoNotResurrectTemporaryBlocksOrRisk() throws Exception {
    var shortStore =
        new com.securegate.enforcer.infrastructure.RedisBlockPolicyStore(redis, 1, 1, 1, 1, 1);
    String user = UUID.randomUUID().toString();
    String sid = UUID.randomUUID().toString();
    var failed = failure(user, "1", UUID.randomUUID().toString(), "203.0.113.20", Instant.now());
    var risk =
        blockEvent(
            "auth.login.suspicious",
            "auth:login",
            java.util.Map.of(
                "userId",
                user,
                "version",
                "1",
                "sid",
                sid,
                "riskScore",
                "80",
                "reasons",
                "DEVICE_UNKNOWN",
                "remoteAddress",
                "203.0.113.21"));
    publish(failed);
    publish(risk);
    try (var worker = blockWorker(shortStore)) {
      worker.runOnce();
    }
    assertTrue(redis.hasKey(blockKey("blocked:account", user + ":1")));
    assertTrue(redis.hasKey(blockKey("risk", sid)));
    assertFalse(redis.hasKey(blockKey("blocked:ip", "203.0.113.21")));
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while ((Boolean.TRUE.equals(redis.hasKey(blockKey("blocked:account", user + ":1")))
            || Boolean.TRUE.equals(redis.hasKey(blockKey("risk", sid))))
        && System.nanoTime() < deadline) {
      Thread.sleep(25);
    }
    publish(failed);
    publish(risk);
    publish(
        failure(
            user,
            "1",
            UUID.randomUUID().toString(),
            "203.0.113.20",
            Instant.now().minusSeconds(301)));
    try (var worker = blockWorker(shortStore)) {
      worker.runOnce();
    }
    assertFalse(redis.hasKey(blockKey("blocked:account", user + ":1")));
    assertFalse(redis.hasKey(blockKey("risk", sid)));
    assertEquals(0, blockCount(""));
  }

  @Test
  void projectionFailureBeforeCompletionRequeuesAndDoesNotPartiallyCount() throws Exception {
    String user = UUID.randomUUID().toString();
    String ip = "203.0.113.30";
    var event = failure(user, "1", UUID.randomUUID().toString(), ip, Instant.now());
    publish(event);
    redis.opsForValue().set(blockKey("attempts:ip", ip), "synthetic wrong type");
    try (var worker = blockWorker(blockStore)) {
      worker.runOnce();
    }
    assertEquals(1, blockCount(""));
    assertFalse(redis.hasKey("securegate:{block-policy}:event:" + event.eventId()));
    assertFalse(redis.hasKey(blockKey("attempts:account", user + ":1")));
    redis.delete(blockKey("attempts:ip", ip));
    try (var worker = blockWorker(blockStore)) {
      worker.runOnce();
    }
    assertEquals(0, blockCount(""));
    assertEquals(1L, redis.opsForZSet().zCard(blockKey("attempts:account", user + ":1")));
  }

  @Test
  void concurrentWorkersAndReplayAfterEffectPreserveOneAttemptAndEventIdConflictsGoToDlq()
      throws Exception {
    String user = UUID.randomUUID().toString();
    var event = failure(user, "1", UUID.randomUUID().toString(), null, Instant.now());
    var effect = com.securegate.enforcer.domain.BlockPolicyEffect.from(event);
    var fingerprint = RedisRevocationStore.fingerprint(json.writeValueAsBytes(event));
    assertEquals(
        com.securegate.enforcer.application.port.BlockPolicyStore.Result.APPLIED,
        blockStore.apply(effect, fingerprint));
    publish(event);
    publish(event);
    try (var first = blockWorker(blockStore);
        var second = blockWorker(blockStore)) {
      CompletableFuture.allOf(
              CompletableFuture.runAsync(first::runOnce),
              CompletableFuture.runAsync(second::runOnce))
          .get(10, TimeUnit.SECONDS);
    }
    assertEquals(1L, redis.opsForZSet().zCard(blockKey("attempts:account", user + ":1")));
    var changed = failure(user, "2", UUID.randomUUID().toString(), null, Instant.now());
    var conflict =
        new SecurityEvent(
            event.eventId(),
            changed.eventType(),
            1,
            changed.occurredAt(),
            changed.producer(),
            changed.correlationId(),
            changed.actor(),
            changed.context(),
            changed.data());
    publish(conflict);
    try (var worker = blockWorker(blockStore)) {
      worker.runOnce();
    }
    assertEquals(1, blockCount(".dlq"));
    assertFalse(redis.hasKey(blockKey("attempts:account", user + ":2")));
  }

  @Test
  void futureSignalsAndUnknownIdentityEventsAreRejectedWithoutEffects() throws Exception {
    publish(
        failure(
            null,
            null,
            UUID.randomUUID().toString(),
            "203.0.113.40",
            Instant.now().plusSeconds(120)));
    publish(
        blockEvent(
            "identity.user.future",
            UUID.randomUUID().toString(),
            java.util.Map.of("version", "1")));
    try (var worker = blockWorker(blockStore)) {
      worker.runOnce();
    }
    assertEquals(2, blockCount(".dlq"));
    assertFalse(redis.hasKey(blockKey("attempts:ip", "203.0.113.40")));
  }

  private void publishTraced(
      SecurityEvent event, com.rabbitmq.client.AMQP.BasicProperties properties) throws Exception {
    try (var connection = factory().newConnection();
        var channel = connection.createChannel()) {
      channel.confirmSelect();
      channel.basicPublish(
          "security.events", event.eventType(), properties, json.writeValueAsBytes(event));
      assertTrue(channel.waitForConfirms(2000));
    }
  }

  @Test
  void tracedRevocationRequeuesAndRecoversWithDistinctChildrenOfBrokerParent() throws Exception {
    try (var tracing =
        new ConsumerTraceTest.Tracing(
            io.opentelemetry.sdk.trace.samplers.Sampler.traceIdRatioBased(1))) {
      var event = event();
      publishTraced(
          event,
          TestEvents.properties(event)
              .builder()
              .headers(
                  java.util.Map.of(
                      "schemaVersion",
                      1,
                      "traceparent",
                      ConsumerTraceTest.parent(),
                      "baggage",
                      "secret=synthetic"))
              .build());
      var fail = new java.util.concurrent.atomic.AtomicBoolean(true);
      var handler =
          new RevocationMessageHandler(
              json,
              new RevocationHandler(
                  (revocation, fingerprint) -> {
                    assertEquals("1".repeat(32), tracing.tracer.currentSpan().context().traceId());
                    assertEquals("1".repeat(32), org.slf4j.MDC.get("traceId"));
                    if (fail.getAndSet(false)) throw new IllegalStateException("synthetic outage");
                    return store.apply(revocation, fingerprint);
                  }));
      try (var worker =
          new RabbitRevocationWorker(
              factory(),
              json,
              handler::handle,
              meters,
              20,
              100,
              RabbitRevocationWorker.QUEUE,
              tracing.consumer)) {
        worker.runOnce();
        assertNull(tracing.tracer.currentSpan());
        assertNull(org.slf4j.MDC.get("traceId"));
        assertEquals(1, count(""));
        assertNull(redis.opsForValue().get(key(event)));
        worker.runOnce();
        assertEquals(0, count(""));
        assertNotNull(redis.opsForValue().get(key(event)));
      }
      var spans = tracing.consumed();
      assertEquals(2, spans.size());
      assertNotEquals(spans.get(0).getSpanId(), spans.get(1).getSpanId());
      for (var span : spans) {
        assertEquals("2".repeat(16), span.getParentSpanId());
        assertEquals("1".repeat(32), span.getTraceId());
        assertEquals(4, span.getAttributes().size());
        assertTrue(span.getEvents().isEmpty());
      }
      var outcome = io.opentelemetry.api.common.AttributeKey.stringKey("messaging.outcome");
      assertEquals("retry", spans.get(0).getAttributes().get(outcome));
      assertEquals("ack", spans.get(1).getAttributes().get(outcome));
      assertEquals(
          "true",
          spans
              .get(1)
              .getAttributes()
              .get(io.opentelemetry.api.common.AttributeKey.stringKey("messaging.redelivered")));
      assertNull(tracing.tracer.currentSpan());
      assertNull(org.slf4j.MDC.get("spanId"));
    }
  }

  @Test
  void tracedBlockPolicyRejectsPoisonAndNextLegacyMessageGetsIndependentRoot() throws Exception {
    try (var tracing =
        new ConsumerTraceTest.Tracing(
            io.opentelemetry.sdk.trace.samplers.Sampler.traceIdRatioBased(1))) {
      var event =
          blockEvent(
              "identity.user.created",
              UUID.randomUUID().toString(),
              java.util.Map.of("status", "ACTIVE", "version", "0"));
      publishTraced(
          event,
          TestEvents.properties(event)
              .builder()
              .messageId("mismatch")
              .headers(
                  java.util.Map.of("schemaVersion", 1, "traceparent", ConsumerTraceTest.parent()))
              .build());
      publish(event);
      var invoked = new java.util.concurrent.atomic.AtomicBoolean();
      var handler =
          new com.securegate.enforcer.infrastructure.BlockPolicyMessageHandler(
              json,
              new com.securegate.enforcer.application.BlockPolicyHandler(
                  (effect, fingerprint) -> {
                    assertNotEquals(
                        "1".repeat(32), tracing.tracer.currentSpan().context().traceId());
                    invoked.set(true);
                    return blockStore.apply(effect, fingerprint);
                  }));
      try (var worker =
          new RabbitRevocationWorker(
              factory(),
              json,
              handler::handle,
              meters,
              20,
              100,
              RabbitRevocationWorker.BLOCK_POLICY_QUEUE,
              tracing.consumer)) {
        worker.runOnce();
      }
      assertTrue(invoked.get());
      assertEquals(0, blockCount(""));
      assertEquals(1, blockCount(".dlq"));
      var spans = tracing.consumed();
      assertEquals(2, spans.size());
      assertEquals("2".repeat(16), spans.get(0).getParentSpanId());
      assertEquals("0".repeat(16), spans.get(1).getParentSpanId());
      var outcome = io.opentelemetry.api.common.AttributeKey.stringKey("messaging.outcome");
      assertEquals("reject", spans.get(0).getAttributes().get(outcome));
      assertEquals("ack", spans.get(1).getAttributes().get(outcome));
      assertNull(tracing.tracer.currentSpan());
      assertNull(org.slf4j.MDC.get("traceId"));
    }
  }
}

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
    try (var worker = worker(store)) {
      worker.runOnce();
    }
    try (var connection = factory().newConnection();
        var channel = connection.createChannel()) {
      channel.queuePurge(RabbitRevocationWorker.QUEUE);
      channel.queuePurge(RabbitRevocationWorker.QUEUE + ".dlq");
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
    var event = event();
    publish(event);
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
                      100)));
      enabled.register(com.securegate.enforcer.bootstrap.ConsumerConfiguration.class);
      enabled.refresh();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (!Boolean.TRUE.equals(redis.hasKey(key(event))) && System.nanoTime() < deadline) {
        Thread.sleep(25);
      }
      assertNotNull(redis.opsForValue().get(key(event)));
    }
  }
}

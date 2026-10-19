package com.securegate.iam;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.securegate.events.SecurityEventType;
import com.securegate.iam.audit.application.OutboxPublisher;
import com.securegate.iam.audit.application.port.AuditRecord;
import com.securegate.iam.audit.application.port.AuditTrail;
import com.securegate.iam.audit.application.port.EventTransport;
import com.securegate.iam.audit.application.port.OutboxDelivery;
import com.securegate.iam.audit.infrastructure.JdbcOutboxDelivery;
import com.securegate.iam.audit.infrastructure.OutboxWorker;
import com.securegate.iam.audit.infrastructure.RabbitEventTransport;
import com.securegate.iam.audit.infrastructure.RabbitTopology;
import com.securegate.iam.shared.application.port.OperationContext;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OutboxPublicationIT {
  private static final String PASSWORD = UUID.randomUUID().toString();

  @Container
  static final PostgreSQLContainer<?> DATABASE =
      new PostgreSQLContainer<>("postgres:16.15-alpine")
          .withDatabaseName("outbox_test")
          .withUsername("outbox_test")
          .withPassword(UUID.randomUUID().toString());

  @Container
  static final GenericContainer<?> RABBIT =
      new GenericContainer<>(DockerImageName.parse("rabbitmq:4.3.6-management-alpine"))
          .withEnv("RABBITMQ_DEFAULT_USER", "outbox_test")
          .withEnv("RABBITMQ_DEFAULT_PASS", PASSWORD)
          .withEnv("RABBITMQ_DEFAULT_VHOST", "securegate")
          .withExposedPorts(5672, 15672)
          .waitingFor(Wait.forListeningPort())
          .withStartupTimeout(Duration.ofSeconds(120));

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
    registry.add("spring.datasource.username", DATABASE::getUsername);
    registry.add("spring.datasource.password", DATABASE::getPassword);
  }

  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper json;
  @Autowired AuditTrail audit;
  @Autowired PlatformTransactionManager manager;
  @Autowired org.springframework.context.ApplicationContext applicationContext;
  private JdbcOutboxDelivery store;
  private RabbitEventTransport transport;
  private OutboxPublisher publisher;
  private Connection admin;
  private Channel channel;

  private ConnectionFactory factory() {
    var factory = new ConnectionFactory();
    factory.setHost(RABBIT.getHost());
    factory.setPort(RABBIT.getMappedPort(5672));
    factory.setUsername("outbox_test");
    factory.setPassword(PASSWORD);
    factory.setVirtualHost("securegate");
    factory.setAutomaticRecoveryEnabled(false);
    factory.setConnectionTimeout(2000);
    factory.setHandshakeTimeout(2000);
    factory.setChannelRpcTimeout(2000);
    factory.useNio();
    return factory;
  }

  @BeforeEach
  void prepare() throws Exception {
    jdbc.update("DELETE FROM iam_audit.outbox_events");
    store = new JdbcOutboxDelivery(jdbc, manager);
    transport = new RabbitEventTransport(factory(), json, 2000);
    publisher = new OutboxPublisher(store, transport, 30);
    admin = factory().newConnection();
    channel = admin.createChannel();
    new RabbitTopology(json).declare(channel);
    try (var source = getClass().getResourceAsStream("/rabbitmq/definitions.json")) {
      for (var queue : json.readTree(source).path("queues")) {
        channel.queuePurge(queue.path("name").asText());
      }
    }
  }

  @AfterEach
  void cleanup() {
    if (transport != null) {
      transport.close();
    }
    if (admin != null) {
      admin.abort();
    }
  }

  private UUID event() {
    return event("test.outbox", Map.of("version", "1"));
  }

  private UUID event(String type, Map<String, String> data) {
    String correlation = UUID.randomUUID().toString();
    audit.record(
        new AuditRecord(
            new SecurityEventType(type),
            "synthetic",
            new OperationContext("publisher-test", correlation),
            data));
    return jdbc.queryForObject(
        "SELECT event_id FROM iam_audit.outbox_events WHERE payload->>'correlationId'=?",
        UUID.class,
        correlation);
  }

  private void due(UUID id) {
    jdbc.update(
        "UPDATE iam_audit.outbox_events SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE event_id=?",
        id);
  }

  private void expire(UUID id) {
    jdbc.update(
        "UPDATE iam_audit.outbox_events SET lease_until=clock_timestamp()-interval '1 second' WHERE event_id=?",
        id);
  }

  private String payload(UUID id) {
    return jdbc.queryForObject(
        "SELECT payload::text FROM iam_audit.outbox_events WHERE event_id=?", String.class, id);
  }

  private int pending() {
    return jdbc.queryForObject(
        "SELECT count(*) FROM iam_audit.outbox_events WHERE published_at IS NULL", Integer.class);
  }

  @Test
  void confirmedPersistentMessagePreservesPayloadAndRoutesToBothIntendedQueues() throws Exception {
    UUID id =
        event(
            "user.locked",
            Map.of(
                "userId",
                UUID.randomUUID().toString(),
                "version",
                "1",
                "status",
                "LOCKED",
                "reason",
                "ADMINISTRATIVE"));
    String original = payload(id);
    var independent =
        new OutboxPublisher(
            store,
            claim -> {
              assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
              return transport.publish(claim);
            },
            30);
    assertEquals(1, independent.publishBatch(10));
    assertEquals(0, pending());
    var delivered = channel.basicGet("audit.persist", true);
    assertNotNull(delivered);
    assertEquals(original, new String(delivered.getBody(), StandardCharsets.UTF_8));
    assertEquals(id.toString(), delivered.getProps().getMessageId());
    assertEquals(2, delivered.getProps().getDeliveryMode());
    assertEquals("application/json", delivered.getProps().getContentType());
    assertEquals("user.locked", delivered.getEnvelope().getRoutingKey());
    assertEquals(1, delivered.getProps().getHeaders().get("schemaVersion"));
    assertNotNull(channel.basicGet("enforcer.block-policy", true));
    assertNull(channel.basicGet("enforcer.revocation", true));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT attempts FROM iam_audit.outbox_events WHERE event_id=?", Integer.class, id));
  }

  @Test
  void mandatoryReturnOverridesAckAndRetryWaitsUntilDue() throws Exception {
    event();
    assertEquals(1, publisher.publishBatch(1));
    channel.basicGet("audit.persist", true);
    channel.queueUnbind("audit.persist", RabbitEventTransport.EXCHANGE, "#");
    UUID id = event();
    String original = payload(id);
    assertEquals(0, publisher.publishBatch(1));
    assertEquals(1, pending());
    assertEquals(
        "UNROUTABLE",
        jdbc.queryForObject(
            "SELECT last_error_code FROM iam_audit.outbox_events WHERE event_id=?",
            String.class,
            id));
    assertNull(channel.basicGet("audit.persist", true));
    channel.queueBind("audit.persist", RabbitEventTransport.EXCHANGE, "#");
    assertEquals(0, publisher.publishBatch(1));
    due(id);
    assertEquals(1, publisher.publishBatch(1));
    var delivered = channel.basicGet("audit.persist", true);
    assertEquals(id.toString(), delivered.getProps().getMessageId());
    assertEquals(original, new String(delivered.getBody(), StandardCharsets.UTF_8));
    assertEquals(
        2,
        jdbc.queryForObject(
            "SELECT attempts FROM iam_audit.outbox_events WHERE event_id=?", Integer.class, id));
  }

  @Test
  void failureAndIdentityEvolutionAreRoutedToBlockPolicyForTheFutureConsumer() throws Exception {
    event(
        "auth.login.failed",
        Map.of(
            "attemptId",
            UUID.randomUUID().toString(),
            "reason",
            "CREDENTIALS_OR_ACCOUNT_REJECTED"));
    event("identity.user.statuschanged", Map.of("version", "2", "status", "ACTIVE"));
    assertEquals(2, publisher.publishBatch(10));
    var first = channel.basicGet("enforcer.block-policy", true);
    var second = channel.basicGet("enforcer.block-policy", true);
    assertNotNull(first);
    assertNotNull(second);
    assertEquals(
        java.util.Set.of("auth.login.failed", "identity.user.statuschanged"),
        java.util.Set.of(
            first.getEnvelope().getRoutingKey(), second.getEnvelope().getRoutingKey()));
    assertEquals(2, channel.queueDeclarePassive("audit.persist").getMessageCount());
  }

  @Test
  void expiredLeaseFencesOldWorkerAndCanBeReclaimed() {
    UUID id = event();
    var first = store.claim(10).orElseThrow();
    assertTrue(store.claim(10).isEmpty());
    expire(id);
    var second = store.claim(10).orElseThrow();
    assertNotEquals(first.claimId(), second.claimId());
    assertEquals(first.payload(), second.payload());
    assertEquals(2, second.attempts());
    assertFalse(store.published(first));
    assertFalse(store.retry(first, EventTransport.Outcome.BROKER_UNAVAILABLE, 2));
    assertTrue(store.published(second));
  }

  @Test
  void lostDatabaseAckAfterBrokerConfirmationProducesSameIdDuplicateNotLoss() throws Exception {
    UUID id = event();
    var interrupted = store.claim(10).orElseThrow();
    assertEquals(EventTransport.Outcome.ACKED, transport.publish(interrupted));
    assertEquals(1, pending());
    expire(id);
    assertEquals(1, publisher.publishBatch(1));
    var first = channel.basicGet("audit.persist", true);
    var second = channel.basicGet("audit.persist", true);
    assertNotNull(first);
    assertNotNull(second);
    assertEquals(first.getProps().getMessageId(), second.getProps().getMessageId());
    assertArrayEquals(first.getBody(), second.getBody());
    assertEquals(id.toString(), second.getProps().getMessageId());
    assertEquals(0, pending());
  }

  @Test
  void concurrentWorkersDoNotClaimTheSameLiveEvent() throws Exception {
    for (int i = 0; i < 20; i++) {
      event();
    }
    var first = CompletableFuture.supplyAsync(() -> publisher.publishBatch(20));
    var second =
        CompletableFuture.supplyAsync(
            () ->
                new OutboxPublisher(new JdbcOutboxDelivery(jdbc, manager), transport, 30)
                    .publishBatch(20));
    assertEquals(20, first.get(30, TimeUnit.SECONDS) + second.get(30, TimeUnit.SECONDS));
    assertEquals(0, pending());
    assertEquals(
        20,
        jdbc.queryForObject(
            "SELECT count(*) FROM iam_audit.outbox_events WHERE attempts=1", Integer.class));
    var ids = new HashSet<String>();
    for (int i = 0; i < 20; i++) {
      var message = channel.basicGet("audit.persist", true);
      assertNotNull(message);
      ids.add(message.getProps().getMessageId());
    }
    assertEquals(20, ids.size());
  }

  @Test
  void rollbackIsNeverPublishedAndAmbientBusinessTransactionsAreRejected() throws Exception {
    var transactions = new TransactionTemplate(manager);
    transactions.executeWithoutResult(
        status -> {
          event();
          assertThrows(IllegalStateException.class, () -> publisher.publishBatch(1));
          try {
            assertEquals(
                0,
                CompletableFuture.supplyAsync(() -> publisher.publishBatch(1))
                    .get(5, TimeUnit.SECONDS));
          } catch (Exception failure) {
            throw new IllegalStateException(failure);
          }
          status.setRollbackOnly();
        });
    assertEquals(0, pending());
    assertNull(channel.basicGet("audit.persist", true));
  }

  @Test
  void brokerOutageKeepsEventPendingAndRecoveryPublishesIt() throws Exception {
    UUID id = event();
    assertEquals(0, RABBIT.execInContainer("rabbitmqctl", "stop_app").getExitCode());
    try {
      assertEquals(0, publisher.publishBatch(1));
      assertEquals(1, pending());
      assertTrue(
          java.util.Set.of("BROKER_UNAVAILABLE", "CONFIRM_TIMEOUT")
              .contains(
                  jdbc.queryForObject(
                      "SELECT last_error_code FROM iam_audit.outbox_events WHERE event_id=?",
                      String.class,
                      id)));
    } finally {
      assertEquals(0, RABBIT.execInContainer("rabbitmqctl", "start_app").getExitCode());
    }
    due(id);
    assertEquals(1, publisher.publishBatch(1));
    admin = factory().newConnection();
    channel = admin.createChannel();
    assertEquals(id.toString(), channel.basicGet("audit.persist", true).getProps().getMessageId());
  }

  @Test
  void scheduledWorkerRecoversAfterAnInfrastructureException() throws Exception {
    UUID id = event();
    var failing =
        org.mockito.Mockito.mock(
            OutboxDelivery.class, org.mockito.AdditionalAnswers.delegatesTo(store));
    // Delegate all calls except the first claim, to exercise the scheduled error boundary.
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    org.mockito.Mockito.doAnswer(
            invocation -> {
              if (calls.getAndIncrement() == 0) {
                throw new IllegalStateException("synthetic infrastructure outage");
              }
              return store.claim(30);
            })
        .when(failing)
        .claim(30);
    try (var worker = new OutboxWorker(new OutboxPublisher(failing, transport, 30), 10, 100)) {
      worker.start();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (pending() > 0 && System.nanoTime() < deadline) {
        Thread.sleep(50);
      }
      assertEquals(0, pending());
    }
    assertEquals(id.toString(), channel.basicGet("audit.persist", true).getProps().getMessageId());
  }

  @Test
  void optInConfigurationBindsCredentialsAndStartsThePublisherLifecycle() throws Exception {
    UUID id = event();
    try (var enabled =
        new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
      enabled.setParent(applicationContext);
      enabled
          .getEnvironment()
          .getPropertySources()
          .addFirst(
              new org.springframework.core.env.MapPropertySource(
                  "publisher-test",
                  Map.of(
                      "iam.outbox.publisher.enabled",
                      true,
                      "iam.outbox.publisher.host",
                      RABBIT.getHost(),
                      "iam.outbox.publisher.port",
                      RABBIT.getMappedPort(5672),
                      "iam.outbox.publisher.virtual-host",
                      "securegate",
                      "iam.outbox.publisher.username",
                      "outbox_test",
                      "iam.outbox.publisher.password",
                      PASSWORD,
                      "iam.outbox.publisher.poll-millis",
                      100)));
      enabled.register(com.securegate.iam.bootstrap.OutboxPublisherConfiguration.class);
      enabled.refresh();
      assertNotNull(enabled.getBean(OutboxPublisher.class));
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (pending() > 0 && System.nanoTime() < deadline) {
        Thread.sleep(50);
      }
      assertEquals(0, pending());
    }
    assertEquals(id.toString(), channel.basicGet("audit.persist", true).getProps().getMessageId());
  }

  @Test
  void invalidRetryArgumentsNeverMutateDeliveryState() {
    event();
    var claim = store.claim(10).orElseThrow();
    assertThrows(
        IllegalArgumentException.class, () -> store.retry(claim, EventTransport.Outcome.ACKED, 2));
    assertThrows(
        IllegalArgumentException.class, () -> store.retry(claim, EventTransport.Outcome.NACK, 0));
    assertThrows(IllegalArgumentException.class, () -> store.claim(1));
    assertEquals(1, pending());
  }

  @Test
  void retryTtlReturnsOnlyToItsConsumerAndRejectedMessageGoesToItsDlq() throws Exception {
    UUID id = event();
    byte[] body = payload(id).getBytes(StandardCharsets.UTF_8);
    var properties =
        new com.rabbitmq.client.AMQP.BasicProperties.Builder()
            .deliveryMode(2)
            .messageId(id.toString())
            .type("test.outbox")
            .contentType("application/json")
            .build();
    channel.basicPublish("security.events.retry", "enforcer.revocation", properties, body);
    com.rabbitmq.client.GetResponse message = null;
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    while (message == null && System.nanoTime() < deadline) {
      message = channel.basicGet("enforcer.revocation", false);
      if (message == null) {
        Thread.sleep(100);
      }
    }
    assertNotNull(message);
    assertArrayEquals(body, message.getBody());
    assertNull(channel.basicGet("audit.persist", true));
    assertNull(channel.basicGet("enforcer.block-policy", true));
    channel.basicNack(message.getEnvelope().getDeliveryTag(), false, false);
    com.rabbitmq.client.GetResponse dead = null;
    deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (dead == null && System.nanoTime() < deadline) {
      dead = channel.basicGet("enforcer.revocation.dlq", true);
      if (dead == null) {
        Thread.sleep(50);
      }
    }
    assertNotNull(dead);
    assertArrayEquals(body, dead.getBody());
    assertNotNull(dead.getProps().getHeaders().get("x-death"));
  }
}

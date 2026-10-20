package com.securegate.iam;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.rabbitmq.client.*;
import com.securegate.events.SecurityEvent;
import com.securegate.iam.audit.application.port.AuditEventStore;
import com.securegate.iam.audit.infrastructure.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class AuditIngestionIT {
  private static final String INGEST_PASSWORD = secret();
  private static final String BROKER_PASSWORD = secret();
  private static final ObjectMapper JSON =
      new ObjectMapper()
          .findAndRegisterModules()
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  @Container
  static final PostgreSQLContainer<?> DATABASE =
      new PostgreSQLContainer<>("postgres:16.15-alpine")
          .withDatabaseName("audit_ingestion")
          .withUsername("audit_owner")
          .withPassword(secret());

  @Container
  static final GenericContainer<?> RABBIT =
      new GenericContainer<>("rabbitmq:4.3.6-management-alpine")
          .withEnv("RABBITMQ_DEFAULT_USER", "audit_test")
          .withEnv("RABBITMQ_DEFAULT_PASS", BROKER_PASSWORD)
          .withEnv("RABBITMQ_DEFAULT_VHOST", "securegate")
          .withExposedPorts(5672)
          .waitingFor(Wait.forListeningPort())
          .withStartupTimeout(Duration.ofSeconds(120));

  private static AuditDatabaseConnection ingest;
  private static JdbcAuditEventStore store;
  private static JdbcTemplate owner;
  private static Path credential;
  private Connection admin;
  private Channel channel;
  private SimpleMeterRegistry meters;
  private RabbitAuditWorker worker;

  @BeforeAll
  static void database() throws Exception {
    String url = DATABASE.getJdbcUrl().split("\\?")[0];
    Flyway.configure()
        .dataSource(url, DATABASE.getUsername(), DATABASE.getPassword())
        .load()
        .migrate();
    var source = new DriverManagerDataSource(url, DATABASE.getUsername(), DATABASE.getPassword());
    owner = new JdbcTemplate(source);
    try (var connection =
        DriverManager.getConnection(url, DATABASE.getUsername(), DATABASE.getPassword())) {
      try (var setting = connection.prepareStatement("SELECT set_config(?, ?, false)")) {
        for (var entry :
            Map.of(
                    "securegate.securegate_audit_ingest_password",
                    INGEST_PASSWORD,
                    "securegate.securegate_audit_maintenance_password",
                    secret())
                .entrySet()) {
          setting.setString(1, entry.getKey());
          setting.setString(2, entry.getValue());
          setting.execute();
        }
      }
      try (var statement = connection.createStatement()) {
        statement.execute(Files.readString(Path.of("../infra/postgres/audit-privileges.sql")));
      }
    }
    credential = Files.createTempFile("audit-ingestion-", ".credential");
    Files.writeString(credential, INGEST_PASSWORD);
    ingest =
        new AuditDatabaseConnection(
            url,
            "securegate_audit_ingest_login",
            credential.toString(),
            "securegate_audit_ingest_login");
    store = new JdbcAuditEventStore(ingest.jdbc(), JSON, ingest.transactions());
  }

  @AfterAll
  static void cleanup() throws Exception {
    if (ingest != null) {
      ingest.close();
    }
    if (credential != null) {
      Files.deleteIfExists(credential);
    }
  }

  @BeforeEach
  void prepare() throws Exception {
    admin = factory().newConnection("audit-test-admin");
    channel = admin.createChannel();
    new RabbitTopology(JSON).declare(channel);
    channel.queuePurge("audit.persist");
    channel.queuePurge("audit.persist.dlq");
    meters = new SimpleMeterRegistry();
    worker = new RabbitAuditWorker(factory(), JSON, store, meters, 20, 1000);
  }

  @AfterEach
  void close() throws Exception {
    worker.close();
    admin.close();
    meters.close();
  }

  @Test
  void boundedTicksLeaveExcessMessagesForTheNextBatch() throws Exception {
    for (int i = 0; i < 21; i++) {
      publish(event());
    }
    worker.runOnce();
    assertEquals(1, ready("audit.persist"));
    assertEquals(20, count("APPENDED"));
    worker.runOnce();
    assertEquals(0, ready("audit.persist"));
    assertEquals(21, count("APPENDED"));
  }

  @Test
  void commitsBatchAndCanonicalDuplicatesWithoutAnotherProducerOrOutboxFact() throws Exception {
    var first = event();
    var second = event();
    long before = owner.queryForObject("SELECT count(*) FROM iam_audit.outbox_events", Long.class);
    publish(first);
    publish(second);
    publish(first);
    worker.runOnce();
    assertEquals(1, rows(first));
    assertEquals(1, rows(second));
    assertEquals(0, ready("audit.persist"));
    assertEquals(0, ready("audit.persist.dlq"));
    assertEquals(2, count("APPENDED"));
    assertEquals(1, count("DUPLICATE"));
    assertEquals(1, meters.get("iam.audit.consumer.batches").counter().count());
    assertEquals(
        before, owner.queryForObject("SELECT count(*) FROM iam_audit.outbox_events", Long.class));
    assertEquals(
        "securegate_audit_ingest_login",
        ingest.jdbc().queryForObject("SELECT current_user", String.class));
  }

  @Test
  void conflictRollsBackWholeBatchThenIsolatesPoisonWithoutBlockingValidNeighbors()
      throws Exception {
    var original = event();
    var valid = event();
    store.append(original);
    var conflict =
        new SecurityEvent(
            original.eventId(),
            original.eventType(),
            1,
            original.occurredAt(),
            original.producer(),
            original.correlationId(),
            "changed",
            original.context(),
            original.data());
    assertThrows(DataAccessException.class, () -> store.appendBatch(List.of(valid, conflict)));
    assertEquals(0, rows(valid));
    assertEquals(0, receipts(valid));
    publish(valid);
    publish(conflict);
    publish(original);
    worker.runOnce();
    assertEquals(1, rows(valid));
    assertEquals(1, rows(original));
    assertEquals(0, ready("audit.persist"));
    assertEquals(1, deadLetters());
    assertEquals(1, count("CONFLICT"));
    assertEquals(1, count("DUPLICATE"));
  }

  @Test
  void invalidPayloadAndHeaderGoToDlqWhileAdjacentValidMessageCommits() throws Exception {
    var valid = event();
    publishRaw(valid, "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8), properties(valid));
    publishRaw(
        valid,
        JSON.writeValueAsBytes(valid),
        properties(valid).builder().messageId(UUID.randomUUID().toString()).build());
    publish(valid);
    worker.runOnce();
    assertEquals(1, rows(valid));
    assertEquals(2, deadLetters());
    assertEquals(2, count("INVALID"));
    assertEquals(0, ready("audit.persist"));
  }

  @Test
  void missingPartitionRequeuesEntireRolledBackBatchAndRecoversAfterOwnerRepair() throws Exception {
    var current = event();
    var future =
        new SecurityEvent(
            UUID.randomUUID(),
            "test.audit",
            1,
            Instant.parse("2037-06-01T00:00:00Z"),
            "iam-core",
            "synthetic",
            "synthetic",
            Map.of("resource", "synthetic"),
            Map.of());
    publish(current);
    publish(future);
    worker.runOnce();
    assertEquals(0, rows(current));
    assertEquals(0, receipts(current));
    assertEquals(0, receipts(future));
    assertEquals(2, count("RETRY"));
    assertEquals(0, ready("audit.persist.dlq"));
    owner.queryForObject(
        "SELECT iam_audit.ensure_event_partitions(DATE '2037-06-01',1)", Integer.class);
    worker.runOnce();
    assertEquals(1, rows(current));
    assertEquals(1, rows(future));
    assertEquals(0, ready("audit.persist"));
  }

  @Test
  void databaseLockTimeoutDoesNotAckAndNextTickRecovers() throws Exception {
    var event = event();
    publish(event);
    try (var blocker = DATABASE.createConnection("")) {
      blocker.setAutoCommit(false);
      blocker.createStatement().execute("LOCK TABLE iam_audit.event_log IN ACCESS EXCLUSIVE MODE");
      assertTimeout(Duration.ofSeconds(5), worker::runOnce);
      assertEquals(1, count("RETRY"));
      blocker.rollback();
    }
    assertEquals(0, receipts(event));
    worker.runOnce();
    assertEquals(1, rows(event));
    assertEquals(0, ready("audit.persist"));
  }

  @Test
  void connectionLossAfterCommitBeforeAckReplaysAsDuplicateAfterReconnect() throws Exception {
    var event = event();
    var live = new AtomicReference<Connection>();
    var factory = spy(factory());
    doAnswer(
            invocation -> {
              var connection = (Connection) invocation.callRealMethod();
              live.set(connection);
              return connection;
            })
        .when(factory)
        .newConnection(anyString());
    var delegated =
        new AuditEventStore() {
          private boolean fail = true;

          @Override
          public Result append(SecurityEvent value) {
            return store.append(value);
          }

          @Override
          public List<Result> appendBatch(List<SecurityEvent> values) {
            var results = store.appendBatch(values);
            if (fail) {
              fail = false;
              live.get().abort();
            }
            return results;
          }
        };
    worker.close();
    worker = new RabbitAuditWorker(factory, JSON, delegated, meters, 20, 1000);
    publish(event);
    worker.runOnce();
    assertEquals(1, rows(event));
    assertEquals(1, meters.get("iam.audit.consumer.errors").counter().count());
    // Broker may finish returning the unacked delivery asynchronously after TCP close.
    long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
    while (System.nanoTime() < deadline
        && meters.find("iam.audit.consumer.messages").tag("result", "DUPLICATE").counter()
            == null) {
      worker.runOnce();
      Thread.sleep(50);
    }
    assertEquals(1, count("DUPLICATE"));
    assertEquals(1, rows(event));
    assertEquals(1, receipts(event));
    assertEquals(0, ready("audit.persist"));
    assertEquals(0, ready("audit.persist.dlq"));
  }

  private ConnectionFactory factory() {
    var result = new ConnectionFactory();
    result.setHost(RABBIT.getHost());
    result.setPort(RABBIT.getMappedPort(5672));
    result.setVirtualHost("securegate");
    result.setUsername("audit_test");
    result.setPassword(BROKER_PASSWORD);
    result.setAutomaticRecoveryEnabled(false);
    result.setConnectionTimeout(2000);
    result.setChannelRpcTimeout(2000);
    return result;
  }

  private void publish(SecurityEvent event) throws Exception {
    publishRaw(event, JSON.writeValueAsBytes(event), properties(event));
  }

  private void publishRaw(SecurityEvent event, byte[] body, AMQP.BasicProperties properties)
      throws Exception {
    channel.confirmSelect();
    channel.basicPublish("security.events", event.eventType(), properties, body);
    channel.waitForConfirmsOrDie(2000);
  }

  private AMQP.BasicProperties properties(SecurityEvent event) {
    return new AMQP.BasicProperties.Builder()
        .contentType("application/json")
        .contentEncoding("UTF-8")
        .deliveryMode(2)
        .messageId(event.eventId().toString())
        .type(event.eventType())
        .appId(event.producer())
        .correlationId(event.correlationId())
        .timestamp(Date.from(event.occurredAt()))
        .headers(Map.of("schemaVersion", 1))
        .build();
  }

  private int rows(SecurityEvent event) {
    return owner.queryForObject(
        "SELECT count(*) FROM iam_audit.event_log WHERE id=?", Integer.class, event.eventId());
  }

  private int receipts(SecurityEvent event) {
    return owner.queryForObject(
        "SELECT count(*) FROM iam_audit.event_receipts WHERE id=?", Integer.class, event.eventId());
  }

  private int ready(String queue) throws Exception {
    return channel.queueDeclarePassive(queue).getMessageCount();
  }

  private int deadLetters() throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    int previous = -1;
    while (System.nanoTime() < deadline) {
      int count = ready("audit.persist.dlq");
      if (count > 0 && count == previous) {
        return count;
      }
      previous = count;
      Thread.sleep(50);
    }
    return ready("audit.persist.dlq");
  }

  private double count(String result) {
    return meters.get("iam.audit.consumer.messages").tag("result", result).counter().count();
  }

  private SecurityEvent event() {
    return new SecurityEvent(
        UUID.randomUUID(),
        "test.audit",
        1,
        Instant.now(),
        "iam-core",
        "synthetic",
        "synthetic",
        Map.of("resource", "synthetic"),
        Map.of());
  }

  private static String secret() {
    return UUID.randomUUID().toString().replace("-", "")
        + UUID.randomUUID().toString().replace("-", "");
  }
}

package com.securegate.iam.audit.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.securegate.events.SecurityEvent;
import com.securegate.iam.audit.application.AuditQuery;
import com.securegate.iam.audit.application.AuditQueryService;
import com.securegate.iam.audit.application.port.AuditEventStore;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Date;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Explicit -Dit.test=AuditPerformanceBenchmark only; not part of normal Surefire/Failsafe patterns.
 */
class AuditPerformanceBenchmark {
  private final ObjectMapper json =
      new ObjectMapper()
          .findAndRegisterModules()
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  private final Path output = Path.of("target/audit-benchmark");
  private JdbcTemplate owner;
  private CaptureJdbc jdbc;
  private JdbcAuditEventStore store;
  private JdbcAuditEventQueries queries;
  private LocalDate month;
  private int rows;
  private final List<Map<String, Object>> phases = new ArrayList<>();
  private final List<String> raw =
      Collections.synchronizedList(new ArrayList<>(List.of("phase,size,rows,nanos")));

  @Test
  void measureRealStoreAndQueryPlansAtScale() throws Exception {
    rows = Integer.getInteger("audit.benchmark.rows", 2_000_000);
    if (rows < 3000 || rows > 5_000_000) {
      throw new IllegalArgumentException("Benchmark rows must be 3000..5000000.");
    }
    Files.createDirectories(output.resolve("plans"));
    String ingestPassword = secret();
    Path credential = Files.createTempFile("audit-benchmark-", ".credential");
    var report = new LinkedHashMap<String, Object>();
    report.put("startedAt", Instant.now());
    report.put("requestedRows", rows);
    report.put("java", System.getProperty("java.version"));
    report.put("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
    report.put("jvmMaxHeapBytes", Runtime.getRuntime().maxMemory());
    report.put("containerCpuLimit", 4);
    report.put("containerMemoryBytes", 4L * 1024 * 1024 * 1024);
    report.put(
        "dataset",
        "3 UTC months, 4 types, 1000 resources, 10000 actors, unique correlations; deterministic ids/times; synthetic small payloads");
    try (var database =
        new PostgreSQLContainer<>("postgres:16.15-alpine")
            .withDatabaseName("audit_benchmark")
            .withUsername("audit_owner")
            .withPassword(secret())
            .withCommand(
                "postgres",
                "-c",
                "shared_preload_libraries=pg_stat_statements",
                "-c",
                "pg_stat_statements.track=top",
                "-c",
                "track_io_timing=on")
            .withCreateContainerCmdModifier(
                command ->
                    command
                        .getHostConfig()
                        .withMemory(4L * 1024 * 1024 * 1024)
                        .withNanoCPUs(4_000_000_000L))) {
      database.start();
      String url = database.getJdbcUrl().split("\\?")[0];
      var source = new DriverManagerDataSource(url, database.getUsername(), database.getPassword());
      owner = new JdbcTemplate(source);
      Flyway.configure().dataSource(source).load().migrate();
      owner.execute("CREATE EXTENSION pg_stat_statements");
      provision(source, ingestPassword);
      Files.writeString(credential, ingestPassword);
      month =
          owner.queryForObject(
              "SELECT date_trunc('month',clock_timestamp() AT TIME ZONE 'UTC')::date",
              LocalDate.class);
      owner.queryForObject(
          "SELECT iam_audit.ensure_event_partitions(?,3)",
          Integer.class,
          Date.valueOf(month.minusMonths(2)));
      report.put("postgres", owner.queryForObject("SELECT version()", String.class));
      report.put(
          "postgresSettings",
          owner.queryForList(
              "SELECT name,setting,unit FROM pg_settings WHERE name IN ('shared_buffers','work_mem','effective_cache_size','max_connections','max_parallel_workers_per_gather','fsync','synchronous_commit','track_io_timing','pg_stat_statements.track') ORDER BY name"));
      try (var connection =
          new AuditDatabaseConnection(
              url,
              "securegate_audit_ingest_login",
              credential.toString(),
              "securegate_audit_ingest_login")) {
        jdbc = new CaptureJdbc(connection.jdbc().getDataSource());
        jdbc.setQueryTimeout(5);
        store = new JdbcAuditEventStore(jdbc, json, connection.transactions());
        queries = new JdbcAuditEventQueries(jdbc, json, connection.transactions());
        report.put("login", jdbc.queryForObject("SELECT current_user", String.class));
        long gcBefore = gcMillis();
        phases.add(ingest("mass", 0, rows, 100, 2));
        long next = rows;
        for (int size : new int[] {1, 20, 100}) {
          int sampleRows = Math.min(rows, 10_000);
          phases.add(ingest("batch-" + size, next, sampleRows, size, 1));
          next += sampleRows;
        }
        assertEquals(
            next, owner.queryForObject("SELECT count(*) FROM iam_audit.event_log", Long.class));
        assertEquals(
            next,
            owner.queryForObject("SELECT count(*) FROM iam_audit.event_receipts", Long.class));
        report.put("finalRows", next);
        report.put("gcMillisDuringIngestion", gcMillis() - gcBefore);
        owner.execute("ANALYZE iam_audit.event_log");
        owner.execute("ANALYZE iam_audit.event_receipts");
        var scenarios = new LinkedHashMap<String, AuditQuery>();
        scenarios.put("time", query(null, null, null, null, 100, null));
        scenarios.put("type", query("benchmark.event.a", null, null, null, 100, null));
        scenarios.put("resource", query(null, "resource:44", null, null, 100, null));
        scenarios.put("actor", query(null, null, "actor-44", null, 100, null));
        scenarios.put("correlation", query(null, null, null, "bench-44", 100, null));
        scenarios.put(
            "combined", query("benchmark.event.a", "resource:44", "actor-44", null, 100, null));
        var measurements = new LinkedHashMap<String, Object>();
        for (var scenario : scenarios.entrySet()) {
          for (int i = 0; i < 5; i++) {
            queries.find(scenario.getValue());
          }
          var times = new ArrayList<Long>();
          int returned = 0;
          for (int i = 0; i < 30; i++) {
            long started = System.nanoTime();
            var result = queries.find(scenario.getValue());
            long nanos = System.nanoTime() - started;
            times.add(nanos);
            returned = result.size();
            raw.add("query-" + scenario.getKey() + ",100," + returned + "," + nanos);
          }
          var summary = new LinkedHashMap<String, Object>(stats(times));
          summary.put("returnedRows", returned);
          summary.put("plan", explain(scenario.getKey(), jdbc.last));
          measurements.put(scenario.getKey(), summary);
        }
        report.put("queries", measurements);
        report.put("nPlusOne", nPlusOne());
        report.put("deepPagination", deepPagination());
        report.put(
            "relations",
            owner.queryForList(
                "SELECT c.relname,pg_total_relation_size(c.oid) AS bytes FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='iam_audit' AND c.relkind='r' ORDER BY c.relname"));
        report.put("phases", phases);
        report.put("finishedAt", Instant.now());
        json.writerWithDefaultPrettyPrinter()
            .writeValue(output.resolve("summary.json").toFile(), report);
        Files.write(output.resolve("samples.csv"), raw);
        System.out.println(
            "AUDIT_BENCHMARK_COMPLETE rows=" + next + " report=" + output.toAbsolutePath());
      }
    } finally {
      Files.deleteIfExists(credential);
    }
  }

  private Map<String, Object> ingest(String phase, long offset, int count, int size, int writers)
      throws Exception {
    var latencies = new ConcurrentLinkedQueue<Long>();
    var completed = new java.util.concurrent.atomic.AtomicInteger();
    long started = System.nanoTime();
    try (var executor = Executors.newFixedThreadPool(writers)) {
      var tasks = new ArrayList<Future<?>>();
      for (int worker = 0; worker < writers; worker++) {
        int number = worker;
        tasks.add(
            executor.submit(
                () -> {
                  for (int start = number * size; start < count; start += writers * size) {
                    long batchStarted = System.nanoTime();
                    int batchRows = Math.min(size, count - start);
                    var events = new ArrayList<SecurityEvent>(batchRows);
                    for (int i = 0; i < batchRows; i++) {
                      events.add(event(offset + start + i));
                    }
                    var results = store.appendBatch(events);
                    assertEquals(batchRows, results.size());
                    assertTrue(
                        results.stream()
                            .allMatch(result -> result == AuditEventStore.Result.APPENDED));
                    long nanos = System.nanoTime() - batchStarted;
                    latencies.add(nanos);
                    raw.add(phase + "," + size + "," + batchRows + "," + nanos);
                    int done = completed.addAndGet(batchRows);
                    if (done % 100_000 == 0) {
                      System.out.println(
                          "AUDIT_BENCHMARK_PROGRESS phase=" + phase + " rows=" + done);
                    }
                  }
                }));
      }
      for (var task : tasks) {
        task.get(30, TimeUnit.MINUTES);
      }
    }
    double seconds = (System.nanoTime() - started) / 1_000_000_000.0;
    var result = new LinkedHashMap<String, Object>(stats(new ArrayList<>(latencies)));
    result.put("phase", phase);
    result.put("rows", count);
    result.put("batchSize", size);
    result.put("writers", writers);
    result.put("seconds", seconds);
    result.put("rowsPerSecond", count / seconds);
    return result;
  }

  private List<Map<String, Object>> nPlusOne() {
    var result = new ArrayList<Map<String, Object>>();
    var service = new AuditQueryService(queries);
    for (int size : new int[] {1, 20, 100}) {
      owner.queryForObject("SELECT pg_stat_statements_reset()::text", String.class);
      var page =
          service.list(
              month.atStartOfDay(ZoneOffset.UTC).toInstant(),
              month.plusMonths(1).atStartOfDay(ZoneOffset.UTC).toInstant(),
              null,
              null,
              null,
              null,
              size,
              null);
      var statements =
          owner.queryForList(
              "SELECT query,calls,rows FROM pg_stat_statements WHERE userid=(SELECT oid FROM pg_roles WHERE rolname='securegate_audit_ingest_login') AND query ~* '^SELECT' ORDER BY query");
      long calls =
          statements.stream().mapToLong(row -> ((Number) row.get("calls")).longValue()).sum();
      assertEquals(1, calls, "Data SELECT count must stay constant as page size grows.");
      assertEquals(size, page.items().size());
      result.add(Map.of("pageSize", size, "selectCalls", calls, "statements", statements));
    }
    return result;
  }

  private Map<String, Object> deepPagination() throws Exception {
    var base = query(null, null, null, null, 100, null);
    queries.find(base);
    Snapshot first = jdbc.last;
    int offset = Math.min(200_000, rows / 6);
    var position =
        owner.queryForObject(
            "SELECT occurred_at,id FROM iam_audit.event_log WHERE occurred_at>=? AND occurred_at<? ORDER BY occurred_at DESC,id DESC OFFSET ? LIMIT 1",
            (row, index) ->
                new AuditQuery.Position(
                    row.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                    row.getObject("id", UUID.class)),
            first.args[0],
            first.args[1],
            offset - 1);
    var page = queries.find(query(null, null, null, null, 100, position));
    Snapshot keyset = jdbc.last;
    var offsetArgs = new ArrayList<Object>(Arrays.asList(first.args));
    offsetArgs.add(offset);
    Snapshot old = new Snapshot(first.sql + " OFFSET ?", offsetArgs.toArray());
    var expected = jdbc.query(old.sql, (row, index) -> row.getObject("id", UUID.class), old.args);
    assertEquals(expected, page.stream().map(row -> row.id()).toList());
    var result = new LinkedHashMap<String, Object>();
    result.put("skippedRows", offset);
    for (var entry : Map.of("keyset", keyset, "offset", old).entrySet()) {
      var times = new ArrayList<Long>();
      for (int i = 0; i < 10; i++) {
        long start = System.nanoTime();
        jdbc.query(
            entry.getValue().sql,
            (row, index) -> row.getObject("id", UUID.class),
            entry.getValue().args);
        long nanos = System.nanoTime() - start;
        times.add(nanos);
        raw.add("deep-" + entry.getKey() + ",100,101," + nanos);
      }
      result.put(entry.getKey(), stats(times));
      result.put(entry.getKey() + "Plan", explain("deep-" + entry.getKey(), entry.getValue()));
    }
    return result;
  }

  private Object explain(String name, Snapshot snapshot) throws Exception {
    String text =
        jdbc.queryForObject(
            "EXPLAIN (ANALYZE,BUFFERS,SETTINGS,FORMAT JSON) " + snapshot.sql,
            String.class,
            snapshot.args);
    var plan = json.readTree(text);
    json.writerWithDefaultPrettyPrinter()
        .writeValue(output.resolve("plans/" + name + ".json").toFile(), plan);
    return plan;
  }

  private AuditQuery query(
      String type,
      String resource,
      String actor,
      String correlation,
      int limit,
      AuditQuery.Position position) {
    return new AuditQuery(
        month.atStartOfDay(ZoneOffset.UTC).toInstant(),
        month.plusMonths(1).atStartOfDay(ZoneOffset.UTC).toInstant(),
        type,
        resource,
        actor,
        correlation,
        limit,
        position);
  }

  private SecurityEvent event(long index) {
    var start = month.minusMonths(2 - index % 3).atStartOfDay(ZoneOffset.UTC).toInstant();
    return new SecurityEvent(
        new UUID(0, index + 1),
        "benchmark.event." + (char) ('a' + index % 4),
        1,
        start.plusSeconds(((index / 3) * 37) % (28 * 86400)),
        "iam-core",
        "bench-" + index,
        "actor-" + index % 10000,
        Map.of("resource", "resource:" + index % 1000),
        Map.of("synthetic", "true"));
  }

  private static Map<String, Object> stats(List<Long> nanos) {
    var sorted = nanos.stream().sorted().toList();
    return Map.of(
        "samples",
        sorted.size(),
        "p50Millis",
        percentile(sorted, .5),
        "p95Millis",
        percentile(sorted, .95),
        "p99Millis",
        percentile(sorted, .99));
  }

  private static double percentile(List<Long> sorted, double fraction) {
    return sorted.get(Math.max(0, (int) Math.ceil(sorted.size() * fraction) - 1)) / 1_000_000.0;
  }

  private static long gcMillis() {
    return ManagementFactory.getGarbageCollectorMXBeans().stream()
        .mapToLong(bean -> Math.max(0, bean.getCollectionTime()))
        .sum();
  }

  private static String secret() {
    return UUID.randomUUID().toString().replace("-", "")
        + UUID.randomUUID().toString().replace("-", "");
  }

  private static void provision(DriverManagerDataSource source, String password) throws Exception {
    try (var connection = source.getConnection()) {
      try (var setting = connection.prepareStatement("SELECT set_config(?,?,false)")) {
        for (var entry :
            Map.of(
                    "securegate.securegate_audit_ingest_password",
                    password,
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
  }

  private record Snapshot(String sql, Object[] args) {}

  private static final class CaptureJdbc extends JdbcTemplate {
    private Snapshot last;

    CaptureJdbc(javax.sql.DataSource source) {
      super(source);
    }

    @Override
    public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
      if (sql.startsWith("SELECT id,occurred_at,received_at,payload::text")) {
        last = new Snapshot(sql, args.clone());
      }
      return super.query(sql, mapper, args);
    }
  }
}

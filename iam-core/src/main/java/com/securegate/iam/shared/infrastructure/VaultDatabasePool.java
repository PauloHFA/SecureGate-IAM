package com.securegate.iam.shared.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/** A generation owns its token, lease and physical connections. Never shared with another pool. */
final class VaultDatabasePool {
  final VaultDatabaseSession session;
  final HikariDataSource pool;
  final AtomicInteger borrowers = new AtomicInteger();
  final String lease;
  private final long increment;
  private final MeterRegistry metrics;
  private volatile long leaseDeadline;
  private long renewAt;
  private boolean renewable;
  volatile boolean disposed;
  long retireAt;
  long cleanupAt;
  boolean revoked;
  Future<?> closing;

  VaultDatabasePool(
      String url,
      URI address,
      Path role,
      Path secret,
      MeterRegistry metrics,
      VaultHttpTransport transport) {
    this.metrics = metrics;
    session = new VaultDatabaseSession(address, role, secret, transport);
    HikariDataSource candidate = null;
    try {
      long started = System.nanoTime();
      var data = session.request("database/creds/iam-runtime", null);
      lease = VaultDatabaseSession.text(data, "lease_id");
      increment = data.path("lease_duration").asLong();
      String username = VaultDatabaseSession.text(data.path("data"), "username");
      if (!lease.matches("database/creds/iam-runtime/[A-Za-z0-9_-]{1,200}")
          || increment < 10
          || increment > 3600
          || !data.path("renewable").isBoolean()
          || !username.matches("sg_[A-Za-z0-9]{20}")) {
        throw VaultDatabaseSession.unavailable();
      }
      applyLease(data, started);
      if (deadline() - System.nanoTime() <= Duration.ofSeconds(3).toNanos()) {
        throw VaultDatabaseSession.unavailable();
      }
      var config = new HikariConfig();
      config.setPoolName("iam-vault-runtime");
      config.setJdbcUrl(url);
      config.setUsername(username);
      config.setPassword(VaultDatabaseSession.text(data.path("data"), "password"));
      config.setMaximumPoolSize(10);
      config.setMinimumIdle(0);
      config.setConnectionTimeout(3000);
      config.setValidationTimeout(1000);
      config.addDataSourceProperty("connectTimeout", "3");
      config.addDataSourceProperty("socketTimeout", "5");
      config.setConnectionInitSql(
          "SET statement_timeout='5s'; SET lock_timeout='1s'; SET idle_in_transaction_session_timeout='10s'; SET TIME ZONE 'UTC'");
      candidate = new HikariDataSource(config);
      try (var connection = candidate.getConnection();
          var statement = connection.createStatement();
          var rows =
              statement.executeQuery(
                  "SELECT pg_has_role(current_user,'securegate_iam_runtime','MEMBER') AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication AND NOT rolbypassrls FROM pg_roles WHERE rolname=current_user")) {
        if (!rows.next() || !rows.getBoolean(1)) {
          throw VaultDatabaseSession.unavailable();
        }
      }
      pool = candidate;
      metrics.counter("iam.database.vault.leases", "outcome", "issued").increment();
    } catch (Exception failure) {
      if (candidate != null) {
        candidate.close();
      }
      try {
        session.close();
      } catch (RuntimeException ignored) {
        metrics.counter("iam.database.vault.revocation.failures").increment();
      }
      throw VaultDatabaseSession.unavailable();
    }
  }

  long deadline() {
    return Math.min(leaseDeadline, session.deadline()) - Duration.ofSeconds(2).toNanos();
  }

  boolean valid(long now) {
    return !disposed && deadline() - now > 0;
  }

  boolean due(long now) {
    return now - Math.min(renewAt, session.renewAt()) >= 0;
  }

  void renew(long now) {
    if (now - session.renewAt() >= 0) {
      try {
        session.renew();
        count("token", "success");
      } catch (RuntimeException failure) {
        count("token", "failure");
        throw failure;
      }
    }
    if (now - renewAt >= 0) {
      try {
        if (!renewable) {
          throw new VaultDatabaseSession.Unavailable(false);
        }
        long started = System.nanoTime();
        var response = session.request("sys/leases/renew/" + lease, Map.of("increment", increment));
        applyLease(response, started);
        count("lease", "success");
      } catch (RuntimeException failure) {
        count("lease", "failure");
        throw failure;
      }
    }
  }

  private void applyLease(JsonNode response, long started) {
    long ttl = response.path("lease_duration").asLong();
    if (!lease.equals(VaultDatabaseSession.text(response, "lease_id"))
        || ttl < 1
        || ttl > 3600
        || !response.path("renewable").isBoolean()) {
      throw VaultDatabaseSession.unavailable();
    }
    leaseDeadline = started + Duration.ofSeconds(ttl).toNanos();
    renewAt = started + Duration.ofSeconds(ttl).toNanos() / 2;
    renewable = response.path("renewable").asBoolean();
    if (ttl < 10) {
      throw new VaultDatabaseSession.Unavailable(false);
    }
  }

  private void count(String kind, String outcome) {
    metrics.counter("iam.database.vault.renewals", "kind", kind, "outcome", outcome).increment();
  }

  @Override
  public String toString() {
    return "VaultDatabasePool[redacted]";
  }
}

package com.securegate.iam.shared.infrastructure;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.jdbc.datasource.AbstractDataSource;

/** One lease per process in 9A2b. Runtime renewal and replacement belong to 9A2c. */
public final class VaultDatabaseDataSource extends AbstractDataSource implements AutoCloseable {
  private final VaultDatabaseSession session;
  private final HikariDataSource pool;
  private final long deadline;
  private final MeterRegistry metrics;
  private final AtomicBoolean closed = new AtomicBoolean();
  private final ScheduledThreadPoolExecutor expiry = expiryScheduler();

  private static ScheduledThreadPoolExecutor expiryScheduler() {
    var scheduler =
        new ScheduledThreadPoolExecutor(
            1,
            task -> {
              Thread thread = new Thread(task, "vault-database-expiry");
              thread.setDaemon(true);
              return thread;
            });
    scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    return scheduler;
  }

  public VaultDatabaseDataSource(
      String url, URI address, Path roleFile, Path secretFile, MeterRegistry metrics) {
    this.metrics = metrics;
    try {
      URI jdbcAddress = URI.create(url.substring(5));
      if (!url.startsWith("jdbc:postgresql://")
          || jdbcAddress.getHost() == null
          || jdbcAddress.getUserInfo() != null
          || jdbcAddress.getQuery() != null
          || jdbcAddress.getFragment() != null
          || !jdbcAddress.getPath().matches("/[A-Za-z0-9_-]{1,63}")) {
        throw VaultDatabaseSession.unavailable();
      }
    } catch (RuntimeException invalid) {
      throw VaultDatabaseSession.unavailable();
    }
    session = new VaultDatabaseSession(address, roleFile, secretFile);
    HikariDataSource candidate = null;
    try {
      long started = System.nanoTime();
      var lease = session.request("database/creds/iam-runtime", null);
      String id = VaultDatabaseSession.text(lease, "lease_id");
      long ttl = lease.path("lease_duration").asLong();
      String username = VaultDatabaseSession.text(lease.path("data"), "username");
      if (!id.startsWith("database/creds/iam-runtime/")
          || ttl < 10
          || ttl > 3600
          || !lease.path("renewable").isBoolean()
          || !username.matches("sg_[a-zA-Z0-9]{20}")) {
        throw VaultDatabaseSession.unavailable();
      }
      deadline =
          Math.min(session.deadline(), started + Duration.ofSeconds(ttl).toNanos())
              - Duration.ofSeconds(2).toNanos();
      if (deadline - System.nanoTime() <= Duration.ofSeconds(3).toNanos()) {
        throw VaultDatabaseSession.unavailable();
      }
      var config = new HikariConfig();
      config.setPoolName("iam-vault-runtime");
      config.setJdbcUrl(url);
      config.setUsername(username);
      config.setPassword(VaultDatabaseSession.text(lease.path("data"), "password"));
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
      expiry.schedule(this::close, Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
      metrics.counter("iam.database.vault.leases", "outcome", "issued").increment();
    } catch (Exception failure) {
      if (candidate != null) {
        candidate.close();
      }
      expiry.shutdown();
      revoke();
      throw VaultDatabaseSession.unavailable();
    }
  }

  @Override
  public Connection getConnection() throws SQLException {
    if (closed.get() || deadline - System.nanoTime() <= 0) {
      close();
      throw new SQLException("Vault database lease unavailable.", "08006");
    }
    try {
      Connection connection = pool.getConnection();
      if (closed.get() || deadline - System.nanoTime() <= 0) {
        connection.close();
        close();
        throw new SQLException("Vault database lease unavailable.", "08006");
      }
      return connection;
    } catch (SQLException failure) {
      throw new SQLException("Vault database connection unavailable.", "08006");
    }
  }

  @Override
  public Connection getConnection(String username, String password) throws SQLException {
    throw new SQLException("Explicit credentials are not supported.", "08004");
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      expiry.shutdown();
      pool.close();
      revoke();
      metrics.counter("iam.database.vault.leases", "outcome", "closed").increment();
    }
  }

  private void revoke() {
    try {
      session.close();
    } catch (RuntimeException failure) {
      metrics.counter("iam.database.vault.revocation.failures").increment();
    }
  }

  @Override
  public String toString() {
    return "VaultDatabaseDataSource[redacted]";
  }
}

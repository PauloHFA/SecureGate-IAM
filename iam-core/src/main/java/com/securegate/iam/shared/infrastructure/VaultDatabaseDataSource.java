package com.securegate.iam.shared.infrastructure;

import io.micrometer.core.instrument.MeterRegistry;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.jdbc.datasource.AbstractDataSource;

/** Stable transactional DataSource; credentials and physical pools rotate behind it. */
public final class VaultDatabaseDataSource extends AbstractDataSource implements AutoCloseable {
  private final Object state = new Object();
  private final String url;
  private final URI address;
  private final Path roleFile;
  private final Path secretFile;
  private final MeterRegistry metrics;
  private final List<VaultDatabasePool> retiring = new ArrayList<>();
  private final ScheduledThreadPoolExecutor control = scheduler("vault-database-renewal");
  private final ScheduledThreadPoolExecutor expiry = scheduler("vault-database-expiry");
  private final ExecutorService closers =
      Executors.newFixedThreadPool(3, task -> daemon(task, "vault-database-close"));
  private VaultDatabasePool active;
  private boolean closed;

  public VaultDatabaseDataSource(
      String url, URI address, Path roleFile, Path secretFile, MeterRegistry metrics) {
    this.url = url;
    this.address = address;
    this.roleFile = roleFile;
    this.secretFile = secretFile;
    this.metrics = metrics;
    try {
      URI jdbc = URI.create(url.substring(5));
      if (!url.startsWith("jdbc:postgresql://")
          || jdbc.getHost() == null
          || jdbc.getUserInfo() != null
          || jdbc.getQuery() != null
          || jdbc.getFragment() != null
          || !jdbc.getPath().matches("/[A-Za-z0-9_-]{1,63}")) {
        throw VaultDatabaseSession.unavailable();
      }
      active = open();
    } catch (RuntimeException failure) {
      control.shutdown();
      expiry.shutdown();
      closers.shutdown();
      throw VaultDatabaseSession.unavailable();
    }
    metrics.gauge("iam.database.vault.available", this, source -> source.available() ? 1 : 0);
    control.scheduleWithFixedDelay(this::maintain, 250, 250, TimeUnit.MILLISECONDS);
    expiry.scheduleWithFixedDelay(this::expire, 100, 100, TimeUnit.MILLISECONDS);
  }

  private static Thread daemon(Runnable task, String name) {
    var thread = new Thread(task, name);
    thread.setDaemon(true);
    return thread;
  }

  private static ScheduledThreadPoolExecutor scheduler(String name) {
    var executor = new ScheduledThreadPoolExecutor(1, task -> daemon(task, name));
    executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    return executor;
  }

  private VaultDatabasePool open() {
    return new VaultDatabasePool(url, address, roleFile, secretFile, metrics);
  }

  private boolean available() {
    synchronized (state) {
      return !closed && active != null && active.valid(System.nanoTime());
    }
  }

  private void maintain() {
    VaultDatabasePool current;
    synchronized (state) {
      if (closed) {
        return;
      }
      current = active;
    }
    try {
      if (!current.valid(System.nanoTime())) {
        replace(current);
      } else if (current.due(System.nanoTime())) {
        try {
          current.renew(System.nanoTime());
          if (current.disposed) {
            replace(current);
          }
        } catch (VaultDatabaseSession.Unavailable failure) {
          if (failure.terminal) {
            synchronized (state) {
              retire(current, true);
            }
          }
          replace(current);
        }
      }
    } catch (RuntimeException failure) {
      metrics.counter("iam.database.vault.rotations", "outcome", "failure").increment();
    } finally {
      cleanup();
    }
  }

  private void replace(VaultDatabasePool expected) {
    synchronized (state) {
      if (closed || active != expected) {
        return;
      }
      if (retiring.size() >= (retiring.contains(expected) ? 3 : 2)) {
        metrics.counter("iam.database.vault.rotations", "outcome", "deferred").increment();
        return;
      }
    }
    VaultDatabasePool candidate = open();
    synchronized (state) {
      if (!closed && active == expected && candidate.valid(System.nanoTime())) {
        active = candidate;
        retire(expected, false);
        metrics.counter("iam.database.vault.rotations", "outcome", "success").increment();
        return;
      }
    }
    candidate.pool.close();
    try {
      candidate.session.close();
    } catch (RuntimeException failure) {
      metrics.counter("iam.database.vault.revocation.failures").increment();
    }
  }

  // Called only under state. No Vault/SQL operation runs while holding this lock.
  private void retire(VaultDatabasePool pool, boolean force) {
    if (closed
        || (pool.disposed && pool.revoked && pool.closing != null && pool.closing.isDone())) {
      return;
    }
    if (!retiring.contains(pool)) {
      retiring.add(pool);
      pool.retireAt =
          Math.min(pool.deadline(), System.nanoTime() + Duration.ofSeconds(5).toNanos());
    }
    if (!pool.disposed
        && (force || pool.borrowers.get() == 0 || System.nanoTime() - pool.retireAt >= 0)) {
      pool.disposed = true;
      pool.closing = closers.submit(pool.pool::close);
    }
  }

  private void expire() {
    synchronized (state) {
      if (closed) {
        return;
      }
      if (!active.valid(System.nanoTime())) {
        retire(active, true);
      }
      for (var pool : List.copyOf(retiring)) {
        retire(pool, System.nanoTime() - pool.deadline() >= 0);
      }
    }
  }

  private void cleanup() {
    List<VaultDatabasePool> pending;
    synchronized (state) {
      if (closed) {
        return;
      }
      pending = List.copyOf(retiring);
    }
    for (var pool : pending) {
      if (!pool.disposed || System.nanoTime() - pool.cleanupAt < 0) {
        continue;
      }
      if (!pool.revoked) {
        try {
          pool.session.revoke();
          pool.revoked = true;
        } catch (RuntimeException failure) {
          metrics.counter("iam.database.vault.revocation.failures").increment();
          pool.cleanupAt = System.nanoTime() + Duration.ofSeconds(1).toNanos();
          if (System.nanoTime() - pool.session.deadline() < 0) {
            continue;
          }
          try {
            pool.session.close();
          } catch (RuntimeException ignored) {
            /* Vault owns expiration retries. */
          }
          pool.revoked = true;
          metrics.counter("iam.database.vault.cleanup.abandoned").increment();
        }
      }
      if (pool.closing != null && pool.closing.isDone()) {
        synchronized (state) {
          retiring.remove(pool);
        }
        metrics.counter("iam.database.vault.leases", "outcome", "closed").increment();
      }
    }
  }

  @Override
  public Connection getConnection() throws SQLException {
    VaultDatabasePool selected;
    synchronized (state) {
      if (closed || active == null || !active.valid(System.nanoTime())) {
        throw unavailable();
      }
      selected = active;
      selected.borrowers.incrementAndGet();
    }
    Connection connection;
    try {
      connection = selected.pool.getConnection();
      if (!selected.valid(System.nanoTime())) {
        connection.close();
        throw unavailable();
      }
    } catch (SQLException failure) {
      selected.borrowers.decrementAndGet();
      throw unavailable();
    }
    AtomicBoolean returned = new AtomicBoolean();
    return (Connection)
        Proxy.newProxyInstance(
            Connection.class.getClassLoader(),
            new Class<?>[] {Connection.class},
            (proxy, method, args) -> {
              String name = method.getName();
              if (name.equals("toString")) {
                return "VaultDatabaseConnection[redacted]";
              }
              if (name.equals("equals")) {
                return proxy == args[0];
              }
              if (name.equals("hashCode")) {
                return System.identityHashCode(proxy);
              }
              boolean release = name.equals("close") || name.equals("abort");
              if (release && !returned.compareAndSet(false, true)) {
                return null;
              }
              if (name.equals("isClosed")
                  && (returned.get() || !selected.valid(System.nanoTime()))) {
                return true;
              }
              if (!release && (returned.get() || !selected.valid(System.nanoTime()))) {
                throw unavailable();
              }
              try {
                return method.invoke(connection, args);
              } catch (InvocationTargetException failure) {
                throw failure.getCause();
              } finally {
                if (release) {
                  selected.borrowers.decrementAndGet();
                }
              }
            });
  }

  @Override
  public Connection getConnection(String username, String password) throws SQLException {
    throw new SQLException("Explicit credentials are not supported.", "08004");
  }

  private static SQLException unavailable() {
    return new SQLException("Vault database lease unavailable.", "08006");
  }

  @Override
  public void close() {
    List<VaultDatabasePool> pools;
    synchronized (state) {
      if (closed) {
        return;
      }
      closed = true;
      pools = new ArrayList<>(retiring);
      retiring.clear();
      if (active != null && !pools.contains(active)) {
        pools.add(active);
      }
      for (var pool : pools) {
        pool.disposed = true;
      }
      active = null;
    }
    control.shutdown();
    expiry.shutdown();
    for (var pool : pools) {
      pool.pool.close();
      try {
        pool.session.close();
      } catch (RuntimeException failure) {
        metrics.counter("iam.database.vault.revocation.failures").increment();
      }
    }
    closers.shutdown();
  }

  @Override
  public String toString() {
    return "VaultDatabaseDataSource[redacted]";
  }
}

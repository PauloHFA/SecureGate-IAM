package com.securegate.iam.audit.infrastructure;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** Dedicated pool behind a wrapper: Boot still creates the main migration DataSource. */
public final class AuditDatabaseConnection implements AutoCloseable {
  private final HikariDataSource pool;
  private final JdbcTemplate jdbc;

  public AuditDatabaseConnection(
      String url, String username, String passwordFile, String expectedUsername) {
    try {
      var address = java.net.URI.create(url.substring(5));
      if (!url.startsWith("jdbc:postgresql://")
          || address.getHost() == null
          || address.getRawUserInfo() != null
          || address.getRawQuery() != null
          || address.getFragment() != null
          || !address.getPath().matches("/[a-zA-Z0-9_-]{1,63}")
          || !expectedUsername.equals(username)
          || passwordFile == null
          || passwordFile.isBlank()) {
        throw new IllegalArgumentException();
      }
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException("Invalid dedicated audit database configuration.");
    }
    final String password;
    try {
      Path path = Path.of(passwordFile);
      if (Files.size(path) > 4096) {
        throw new IllegalArgumentException();
      }
      password = Files.readString(path).strip();
      if (!password.matches("[0-9a-f]{64}")) {
        throw new IllegalArgumentException();
      }
    } catch (Exception invalid) {
      throw new IllegalArgumentException("Audit database credential unavailable.");
    }
    var settings = new HikariConfig();
    settings.setPoolName(expectedUsername);
    settings.setJdbcUrl(url);
    settings.setUsername(username);
    settings.setPassword(password);
    settings.setMaximumPoolSize(2);
    settings.setMinimumIdle(0);
    settings.setConnectionTimeout(3000);
    settings.setValidationTimeout(1000);
    settings.addDataSourceProperty("connectTimeout", "3");
    settings.addDataSourceProperty("socketTimeout", "6");
    settings.addDataSourceProperty("cancelSignalTimeout", "2");
    settings.setConnectionInitSql(
        "SET statement_timeout='5s'; SET lock_timeout='1s'; SET idle_in_transaction_session_timeout='10s'; SET TIME ZONE 'UTC'");
    try {
      pool = new HikariDataSource(settings);
    } catch (RuntimeException unavailable) {
      throw new IllegalStateException("Audit database connection unavailable.");
    }
    jdbc = new JdbcTemplate(pool);
    jdbc.setQueryTimeout(5);
  }

  public JdbcTemplate jdbc() {
    return jdbc;
  }

  public DataSourceTransactionManager transactions() {
    return new DataSourceTransactionManager(pool);
  }

  @Override
  public void close() {
    pool.close();
  }
}

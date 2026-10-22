package com.securegate.iam.bootstrap;

import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.Map;
import org.flywaydb.core.Flyway;

/** Administrative process only; never creates the web application or runtime pool. */
public final class DatabaseMigration {
  public static void run(Map<String, String> environment) {
    try {
      String url = environment.get("IAM_MIGRATION_DATABASE_URL");
      String username = environment.get("IAM_MIGRATION_DATABASE_USERNAME");
      String password = environment.get("IAM_MIGRATION_DATABASE_PASSWORD");
      if (url == null
          || !url.startsWith("jdbc:postgresql://")
          || username == null
          || username.isBlank()
          || password == null
          || password.isBlank()) {
        throw new IllegalArgumentException();
      }
      var address = java.net.URI.create(url.substring(5));
      if (address.getHost() == null
          || address.getUserInfo() != null
          || address.getQuery() != null
          || address.getFragment() != null
          || !address.getPath().matches("/[A-Za-z0-9_-]{1,63}")) {
        throw new IllegalArgumentException();
      }
      Flyway.configure().dataSource(url, username, password).cleanDisabled(true).load().migrate();
      try (var stream =
              DatabaseMigration.class.getResourceAsStream("/postgres/runtime-privileges.sql");
          var connection = DriverManager.getConnection(url, username, password);
          var statement = connection.createStatement()) {
        statement.execute(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
      }
    } catch (Exception failure) {
      throw new IllegalStateException("Database migration unavailable.");
    }
  }

  private DatabaseMigration() {}
}

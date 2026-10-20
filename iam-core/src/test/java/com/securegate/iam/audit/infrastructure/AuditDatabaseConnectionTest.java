package com.securegate.iam.audit.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuditDatabaseConnectionTest {
  @TempDir Path directory;

  @Test
  void ownerCredentialsAndEmbeddedUrlSecretsAreRejectedBeforeCreatingAPool() {
    var owner =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new AuditDatabaseConnection(
                    "jdbc:postgresql://localhost/test",
                    "postgres",
                    "absent",
                    "securegate_audit_ingest_login"));
    assertFalse(owner.getMessage().contains("postgres"));
    var embedded =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new AuditDatabaseConnection(
                    "jdbc:postgresql://localhost/test?password=synthetic-private-value",
                    "securegate_audit_ingest_login",
                    "absent",
                    "securegate_audit_ingest_login"));
    assertFalse(embedded.getMessage().contains("synthetic-private-value"));
  }

  @Test
  void absentOrInvalidCredentialFilesCannotFallBackToMainCredentials() throws Exception {
    var path = directory.resolve("credential");
    Files.writeString(path, "synthetic-private-value");
    var failure =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new AuditDatabaseConnection(
                    "jdbc:postgresql://localhost/test",
                    "securegate_audit_ingest_login",
                    path.toString(),
                    "securegate_audit_ingest_login"));
    assertFalse(failure.getMessage().contains("synthetic-private-value"));
    Files.delete(path);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AuditDatabaseConnection(
                "jdbc:postgresql://localhost/test",
                "securegate_audit_ingest_login",
                path.toString(),
                "securegate_audit_ingest_login"));
  }
}

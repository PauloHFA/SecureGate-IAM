package com.securegate.iam.authorization.infrastructure;

import com.securegate.iam.authorization.application.port.PamCredentials;
import com.securegate.iam.shared.infrastructure.VaultDatabaseSession;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

public final class VaultPamCredentials implements PamCredentials {
  private final URI address;
  private final Path role, secret;

  public VaultPamCredentials(URI address, Path role, Path secret) {
    this.address = address;
    this.role = role;
    this.secret = secret;
  }

  private VaultDatabaseSession session() {
    var session = new VaultDatabaseSession(address, role, secret);
    if (session.deadline() - System.nanoTime() > Duration.ofSeconds(300).toNanos()) {
      session.close();
      throw new IllegalStateException("PAM Vault unavailable.");
    }
    return session;
  }

  @Override
  public Issued issue(int ttl) {
    if (!Set.of(10, 30, 60, 120, 240).contains(ttl)) {
      throw new IllegalArgumentException("Invalid PAM TTL.");
    }
    var session = session();
    try {
      var response = session.request("pam-database/creds/diagnostics-" + ttl, null);
      String lease = VaultDatabaseSession.text(response, "lease_id");
      String username = VaultDatabaseSession.text(response.path("data"), "username");
      String password = VaultDatabaseSession.text(response.path("data"), "password");
      int seconds = response.path("lease_duration").asInt();
      if (!lease.matches("pam-database/creds/diagnostics-" + ttl + "/[A-Za-z0-9_-]{1,200}")
          || !username.matches("pam_[A-Za-z0-9]{20}")
          || seconds < 1
          || seconds > ttl) {
        throw new IllegalStateException("PAM Vault unavailable.");
      }
      return new Issued() {
        public String leaseId() {
          return lease;
        }

        public String username() {
          return username;
        }

        public String password() {
          return password;
        }

        public int ttlSeconds() {
          return seconds;
        }

        public void delivered() {
          session.detach();
        }

        public void close() {
          session.close();
        }

        public String toString() {
          return "PamCredential[redacted]";
        }
      };
    } catch (RuntimeException failure) {
      try {
        session.close();
      } catch (RuntimeException ignored) {
      }
      throw new IllegalStateException("PAM Vault unavailable.");
    }
  }

  @Override
  public void revoke(String lease) {
    if (lease == null
        || !lease.matches(
            "pam-database/creds/diagnostics-(10|30|60|120|240)/[A-Za-z0-9_-]{1,200}")) {
      throw new IllegalArgumentException("Invalid PAM lease.");
    }
    try (var session = session()) {
      session.request("sys/leases/revoke/" + lease, Map.of("sync", true));
    }
  }
}

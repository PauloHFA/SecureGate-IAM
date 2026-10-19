package com.securegate.iam.policy.infrastructure;

import com.securegate.events.SecurityEventType;
import com.securegate.iam.audit.application.port.AuditRecord;
import com.securegate.iam.audit.application.port.AuditTrail;
import com.securegate.iam.identity.application.port.IdentityLookup;
import com.securegate.iam.identity.application.port.LoginRiskHistory;
import com.securegate.iam.policy.application.port.RiskEvidence;
import com.securegate.iam.policy.domain.NetworkCidr;
import com.securegate.iam.policy.domain.RiskCalculator;
import com.securegate.iam.policy.domain.RiskSignals;
import com.securegate.iam.shared.application.ConcurrentUpdateException;
import com.securegate.iam.shared.application.ResourceNotFoundException;
import com.securegate.iam.shared.application.port.OperationContext;
import com.securegate.iam.shared.application.port.UnitOfWork;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcRiskEvidence implements RiskEvidence {
  private final JdbcTemplate jdbc;
  private final Clock clock;
  private final LoginRiskHistory history;
  private final IdentityLookup identities;
  private final UnitOfWork transactions;
  private final AuditTrail audit;
  private final Supplier<OperationContext> eventContext;

  public JdbcRiskEvidence(
      JdbcTemplate jdbc,
      Clock clock,
      LoginRiskHistory history,
      IdentityLookup identities,
      UnitOfWork transactions,
      AuditTrail audit,
      Supplier<OperationContext> eventContext) {
    this.jdbc = jdbc;
    this.clock = clock;
    this.history = history;
    this.identities = identities;
    this.transactions = transactions;
    this.audit = audit;
    this.eventContext = eventContext;
  }

  @Override
  public UUID observe(UUID user, long version, UUID context, String hint, String remote) {
    return transactions.execute(
        () -> {
          if (!identities.isCurrent(user, version)) {
            throw new IllegalArgumentException("Stale browser identity");
          }
          UUID candidate = null;
          try {
            candidate = UUID.fromString(hint);
          } catch (IllegalArgumentException | NullPointerException ignored) {
          }
          UUID device = candidate;
          if (device == null
              || jdbc.queryForObject(
                      "SELECT count(*) FROM iam_policy.browser_devices WHERE user_id=? AND device_id=?",
                      Integer.class,
                      user,
                      device)
                  == 0) {
            device = UUID.randomUUID();
            jdbc.update(
                "INSERT INTO iam_policy.browser_devices(user_id,device_id) VALUES (?,?)",
                user,
                device);
          }
          int changed =
              jdbc.update(
                  """
          INSERT INTO iam_policy.browser_contexts(id,user_id,identity_version,device_id,remote_address,observed_at,login_assessed)
          VALUES (?,?,?,?,?::inet,?,false) ON CONFLICT(id) DO UPDATE SET remote_address=excluded.remote_address,observed_at=excluded.observed_at
          WHERE browser_contexts.user_id=excluded.user_id AND browser_contexts.identity_version=excluded.identity_version
            AND browser_contexts.device_id=excluded.device_id AND browser_contexts.closed_at IS NULL
          """,
                  context,
                  user,
                  version,
                  device,
                  NetworkCidr.address(remote),
                  Timestamp.from(clock.instant()));
          if (changed != 1) {
            throw new IllegalStateException("Risk context cannot be reassigned or reopened");
          }
          if (jdbc.update(
                  "UPDATE iam_policy.browser_contexts SET login_assessed=true WHERE id=? AND NOT login_assessed",
                  context)
              == 1) {
            var assessment =
                new RiskCalculator().assess(collect(user, "auth:login", context), clock.instant());
            if (assessment.score() >= 70) {
              var data =
                  new HashMap<>(
                      Map.of(
                          "userId",
                          user.toString(),
                          "version",
                          Long.toString(version),
                          "sid",
                          context.toString(),
                          "riskScore",
                          Integer.toString(assessment.score()),
                          "reasons",
                          String.join(",", assessment.reasons())));
              String observedAddress = NetworkCidr.address(remote);
              if (observedAddress != null) {
                data.put("remoteAddress", observedAddress);
              }
              audit.record(
                  new AuditRecord(
                      new SecurityEventType("auth.login.suspicious"),
                      "auth:login",
                      new OperationContext(user.toString(), eventContext.get().correlationId()),
                      data));
            }
          }
          return device;
        });
  }

  @Override
  public void close(UUID user, UUID context) {
    transactions.execute(
        () -> {
          var versions =
              jdbc.query(
                  "UPDATE iam_policy.browser_contexts SET closed_at=? WHERE id=? AND user_id=? AND closed_at IS NULL RETURNING identity_version",
                  (rs, row) -> rs.getLong(1),
                  Timestamp.from(clock.instant()),
                  context,
                  user);
          if (!versions.isEmpty()) {
            audit.record(
                new AuditRecord(
                    new SecurityEventType("session.terminated"),
                    context.toString(),
                    new OperationContext(user.toString(), eventContext.get().correlationId()),
                    Map.of(
                        "userId",
                        user.toString(),
                        "version",
                        versions.getFirst().toString(),
                        "sid",
                        context.toString(),
                        "reason",
                        "BROWSER_CLOSED")));
          }
          return null;
        });
  }

  @Override
  public RiskSignals collect(UUID user, String resource, UUID context) {
    var now = clock.instant();
    var sensitivity =
        jdbc
            .query(
                "SELECT sensitivity FROM iam_policy.resource_profiles WHERE resource=?",
                (rs, row) -> RiskSignals.Sensitivity.valueOf(rs.getString(1)),
                resource)
            .stream()
            .findFirst()
            .orElse(RiskSignals.Sensitivity.UNKNOWN);
    var trusts =
        jdbc.query(
            """
        SELECT d.trusted_until>? AS device_trusted,
          CASE WHEN c.remote_address IS NULL THEN NULL ELSE EXISTS(
            SELECT 1 FROM iam_policy.trusted_networks n WHERE n.enabled AND c.remote_address <<= n.network) END AS network_trusted
        FROM iam_policy.browser_contexts c JOIN iam_policy.browser_devices d USING(user_id,device_id)
          JOIN iam_identity.users u ON u.id=c.user_id AND u.version=c.identity_version AND u.status='ACTIVE'
        WHERE c.id=? AND c.user_id=? AND c.closed_at IS NULL AND c.observed_at>=? AND c.observed_at<=?
        """,
            (rs, row) ->
                new RiskSignals.Trust[] {
                  rs.getBoolean("device_trusted")
                      ? RiskSignals.Trust.TRUSTED
                      : RiskSignals.Trust.UNTRUSTED,
                  rs.getObject("network_trusted") == null
                      ? RiskSignals.Trust.UNKNOWN
                      : rs.getBoolean("network_trusted")
                          ? RiskSignals.Trust.TRUSTED
                          : RiskSignals.Trust.UNTRUSTED
                },
            Timestamp.from(now),
            context,
            user,
            Timestamp.from(now.minusSeconds(300)),
            Timestamp.from(now.plusSeconds(5)));
    var values =
        trusts.isEmpty()
            ? new RiskSignals.Trust[] {RiskSignals.Trust.UNKNOWN, RiskSignals.Trust.UNKNOWN}
            : trusts.getFirst();
    return new RiskSignals(values[0], values[1], history.recentFailures(user), sensitivity, now);
  }

  @Override
  public List<ContextView> contexts(UUID user) {
    return jdbc.query(
        """
        SELECT c.id,c.device_id,c.observed_at,d.version,d.trusted_until
        FROM iam_policy.browser_contexts c JOIN iam_policy.browser_devices d USING(user_id,device_id)
          JOIN iam_identity.users u ON u.id=c.user_id AND u.version=c.identity_version AND u.status='ACTIVE'
        WHERE c.user_id=? AND c.closed_at IS NULL AND c.observed_at>=? AND c.observed_at<=?
        ORDER BY c.observed_at DESC,c.id LIMIT 20
        """,
        (rs, row) ->
            new ContextView(
                rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class),
                rs.getTimestamp(3).toInstant(),
                rs.getLong(4),
                rs.getTimestamp(5) == null ? null : rs.getTimestamp(5).toInstant()),
        user,
        Timestamp.from(clock.instant().minusSeconds(300)),
        Timestamp.from(clock.instant().plusSeconds(5)));
  }

  @Override
  public ResourceView createResource(String resource, String sensitivity) {
    jdbc.update(
        "INSERT INTO iam_policy.resource_profiles(resource,sensitivity) VALUES (?,?)",
        resource,
        sensitivity);
    return resource(resource);
  }

  @Override
  public ResourceView updateResource(String resource, String sensitivity, long version) {
    resource(resource);
    changed(
        jdbc.update(
            "UPDATE iam_policy.resource_profiles SET sensitivity=?,version=version+1 WHERE resource=? AND version=?",
            sensitivity,
            resource,
            version));
    return resource(resource);
  }

  @Override
  public ResourceView resource(String resource) {
    return jdbc
        .query(
            "SELECT resource,sensitivity,version FROM iam_policy.resource_profiles WHERE resource=?",
            (rs, row) -> new ResourceView(rs.getString(1), rs.getString(2), rs.getLong(3)),
            resource)
        .stream()
        .findFirst()
        .orElseThrow(ResourceNotFoundException::new);
  }

  @Override
  public NetworkView createNetwork(UUID id, String cidr) {
    jdbc.update(
        "INSERT INTO iam_policy.trusted_networks(id,network) VALUES (?,?::inet::cidr)", id, cidr);
    return network(id);
  }

  @Override
  public NetworkView updateNetwork(UUID id, boolean enabled, long version) {
    network(id);
    changed(
        jdbc.update(
            "UPDATE iam_policy.trusted_networks SET enabled=?,version=version+1 WHERE id=? AND version=?",
            enabled,
            id,
            version));
    return network(id);
  }

  private NetworkView network(UUID id) {
    return jdbc
        .query(
            "SELECT id,network::text,enabled,version FROM iam_policy.trusted_networks WHERE id=?",
            (rs, row) ->
                new NetworkView(
                    rs.getObject(1, UUID.class), rs.getString(2), rs.getBoolean(3), rs.getLong(4)),
            id)
        .stream()
        .findFirst()
        .orElseThrow(ResourceNotFoundException::new);
  }

  @Override
  public List<NetworkView> networks() {
    return jdbc.query(
        "SELECT id,network::text,enabled,version FROM iam_policy.trusted_networks ORDER BY id LIMIT 100",
        (rs, row) ->
            new NetworkView(
                rs.getObject(1, UUID.class), rs.getString(2), rs.getBoolean(3), rs.getLong(4)));
  }

  @Override
  public DeviceView device(UUID user, UUID device) {
    return jdbc
        .query(
            "SELECT device_id,version,trusted_until FROM iam_policy.browser_devices WHERE user_id=? AND device_id=?",
            (rs, row) ->
                new DeviceView(
                    rs.getObject(1, UUID.class),
                    rs.getLong(2),
                    rs.getTimestamp(3) == null ? null : rs.getTimestamp(3).toInstant()),
            user,
            device)
        .stream()
        .findFirst()
        .orElseThrow(ResourceNotFoundException::new);
  }

  @Override
  public DeviceView trustDevice(UUID user, UUID device, boolean trusted, int ttl, long version) {
    device(user, device);
    changed(
        jdbc.update(
            "UPDATE iam_policy.browser_devices SET trusted_until=?,version=version+1 WHERE user_id=? AND device_id=? AND version=?",
            trusted ? Timestamp.from(clock.instant().plusSeconds(ttl)) : null,
            user,
            device,
            version));
    return device(user, device);
  }

  private static void changed(int rows) {
    if (rows != 1) {
      throw new ConcurrentUpdateException();
    }
  }
}

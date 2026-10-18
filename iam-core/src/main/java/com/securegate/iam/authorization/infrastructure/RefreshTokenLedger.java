package com.securegate.iam.authorization.infrastructure;

import com.securegate.events.SecurityEventType;
import com.securegate.iam.audit.application.port.AuditRecord;
import com.securegate.iam.audit.application.port.AuditTrail;
import com.securegate.iam.shared.application.port.OperationContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;

/** Only SHA-256 fingerprints of historical refresh values are retained here. */
public final class RefreshTokenLedger {
  private final JdbcTemplate jdbc;
  private final AuditTrail audit;
  private final Supplier<OperationContext> context;

  public RefreshTokenLedger(
      JdbcTemplate jdbc, AuditTrail audit, Supplier<OperationContext> context) {
    this.jdbc = jdbc;
    this.audit = audit;
    this.context = context;
  }

  public void record(OAuth2Authorization authorization) {
    var refresh = authorization.getRefreshToken();
    if (refresh == null) {
      return;
    }
    String hash = hash(refresh.getToken().getTokenValue());
    if (jdbc.queryForObject(
            "SELECT count(*) FROM iam_authorization.refresh_history WHERE token_hash=?",
            Integer.class,
            hash)
        > 0) {
      return;
    }
    jdbc.update(
        "INSERT INTO iam_authorization.refresh_families(authorization_id,registered_client_id,expires_at) VALUES (?,?,?) ON CONFLICT DO NOTHING",
        authorization.getId(),
        authorization.getRegisteredClientId(),
        Timestamp.from(refresh.getToken().getExpiresAt()));
    jdbc.update(
        "INSERT INTO iam_authorization.refresh_history(token_hash,authorization_id,expires_at) VALUES (?,?,?)",
        hash,
        authorization.getId(),
        Timestamp.from(refresh.getToken().getExpiresAt()));
  }

  public Optional<Entry> lockToken(String token) {
    var families =
        jdbc.queryForList(
            "SELECT authorization_id FROM iam_authorization.refresh_history WHERE token_hash=?",
            String.class,
            hash(token));
    if (families.isEmpty() || !lockFamily(families.getFirst())) {
      return Optional.empty();
    }
    // Read the token state in a new statement AFTER acquiring the family lock.
    // A join read started before waiting could retain a stale consumed_at snapshot.
    return jdbc
        .query(
            """
        SELECT f.authorization_id,f.registered_client_id,f.expires_at AS family_expires,f.revoked_at,
               h.expires_at AS token_expires,h.consumed_at
        FROM iam_authorization.refresh_history h JOIN iam_authorization.refresh_families f USING(authorization_id)
        WHERE h.token_hash=?
        """,
            (rs, row) ->
                new Entry(
                    rs.getString("authorization_id"),
                    rs.getString("registered_client_id"),
                    rs.getTimestamp("family_expires").toInstant(),
                    rs.getTimestamp("token_expires").toInstant(),
                    rs.getTimestamp("revoked_at") != null,
                    rs.getTimestamp("consumed_at") != null),
            hash(token))
        .stream()
        .findFirst();
  }

  public boolean lockFamily(String authorizationId) {
    return !jdbc.queryForList(
            "SELECT authorization_id FROM iam_authorization.refresh_families WHERE authorization_id=? FOR UPDATE",
            String.class,
            authorizationId)
        .isEmpty();
  }

  public Optional<Instant> expires(String authorizationId) {
    return jdbc
        .query(
            "SELECT expires_at FROM iam_authorization.refresh_families WHERE authorization_id=?",
            (rs, row) -> rs.getTimestamp(1).toInstant(),
            authorizationId)
        .stream()
        .findFirst();
  }

  public boolean revoked(String authorizationId) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM iam_authorization.refresh_families WHERE authorization_id=? AND revoked_at IS NOT NULL)",
            Boolean.class,
            authorizationId));
  }

  public void consume(String token) {
    jdbc.update(
        "UPDATE iam_authorization.refresh_history SET consumed_at=current_timestamp WHERE token_hash=? AND consumed_at IS NULL",
        hash(token));
  }

  public void revoke(
      String authorizationId, OAuth2AuthorizationService authorizations, String event) {
    int changed =
        jdbc.update(
            "UPDATE iam_authorization.refresh_families SET revoked_at=current_timestamp WHERE authorization_id=? AND revoked_at IS NULL",
            authorizationId);
    if (changed == 0) {
      return;
    }
    var authorization = authorizations.findById(authorizationId);
    if (authorization != null) {
      var builder = OAuth2Authorization.from(authorization);
      if (authorization.getAccessToken() != null) {
        builder.invalidate(authorization.getAccessToken().getToken());
      }
      if (authorization.getRefreshToken() != null) {
        builder.invalidate(authorization.getRefreshToken().getToken());
      }
      authorizations.save(builder.build());
      audit(authorization, event);
    }
  }

  public void audit(OAuth2Authorization authorization, String event) {
    audit.record(
        new AuditRecord(
            new SecurityEventType(event),
            authorization.getId(),
            context.get(),
            Map.of(
                "registeredClientId",
                authorization.getRegisteredClientId(),
                "authorizationId",
                authorization.getId())));
  }

  private static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 unavailable");
    }
  }

  public record Entry(
      String authorizationId,
      String clientId,
      Instant familyExpires,
      Instant tokenExpires,
      boolean revoked,
      boolean consumed) {}
}

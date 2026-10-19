package com.securegate.iam.authorization.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.securegate.iam.authorization.application.port.AuthorizationContinuation;
import com.securegate.iam.authorization.application.port.ContinuationStore;
import com.securegate.iam.identity.application.port.IdentityLookup;
import com.securegate.iam.identity.application.port.MfaProofs;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AuthorizationContinuationServiceTest {
  private final UUID user = UUID.randomUUID();
  private final UUID sid = UUID.randomUUID();
  private final Instant now = Instant.parse("2026-10-19T12:00:00Z");
  private final Clock clock = mock(Clock.class);
  private final IdentityLookup identities = mock(IdentityLookup.class);
  private final MfaProofs proofs = mock(MfaProofs.class);
  private final AtomicReference<ContinuationStore.Pending> pending = new AtomicReference<>();
  private final ContinuationStore store =
      new ContinuationStore() {
        public void save(Pending value) {
          pending.set(value);
        }

        public Optional<Pending> load() {
          return Optional.ofNullable(pending.get());
        }

        public boolean remove(String ticket) {
          Pending value = pending.get();
          return value != null
              && value.ticket().equals(ticket)
              && pending.compareAndSet(value, null);
        }

        public void clear() {
          pending.set(null);
        }
      };
  private final AuthorizationContinuation service =
      new AuthorizationContinuationService(store, identities, proofs, clock);
  private final Map<String, String> parameters =
      Map.of(
          "response_type",
          "code",
          "client_id",
          "web-test",
          "redirect_uri",
          "https://client.example/callback",
          "scope",
          "api:read",
          "state",
          "synthetic-state-1234",
          "code_challenge",
          "A".repeat(43),
          "code_challenge_method",
          "S256");

  @BeforeEach
  void setup() {
    when(clock.instant()).thenReturn(now);
    when(identities.isCurrent(user, 0)).thenReturn(true);
    when(proofs.current(user, 0, sid)).thenReturn(Optional.empty());
  }

  @Test
  void offersContinuationOnlyForAuthoritativeProofAndConsumesOnce() {
    service.begin(user, 0, sid, "Client", parameters);
    var view = service.current(user, 0, sid).orElseThrow();
    assertFalse(view.ready());
    assertEquals(now.plusSeconds(300), view.expiresAt());
    assertThrows(
        AuthorizationContinuation.Unavailable.class,
        () -> service.resume(view.ticket(), user, 0, sid));
    when(proofs.current(user, 0, sid))
        .thenReturn(Optional.of(new MfaProofs.Proof("TOTP", now, now.plusSeconds(300))));
    assertTrue(service.current(user, 0, sid).orElseThrow().ready());
    assertEquals(parameters, service.resume(view.ticket(), user, 0, sid));
    assertThrows(
        AuthorizationContinuation.Unavailable.class,
        () -> service.resume(view.ticket(), user, 0, sid));
    assertTrue(service.current(user, 0, sid).isEmpty());
  }

  @Test
  void bindsToUserVersionSessionAndDoesNotExtendExpiryOnRead() {
    service.begin(user, 0, sid, "Client", parameters);
    assertTrue(service.current(UUID.randomUUID(), 0, sid).isEmpty());
    assertTrue(service.current(user, 1, sid).isEmpty());
    assertTrue(service.current(user, 0, UUID.randomUUID()).isEmpty());
    assertTrue(service.current(null, 0, sid).isEmpty());
    assertTrue(service.current(user, 0, null).isEmpty());
    when(clock.instant()).thenReturn(now.plusSeconds(299));
    assertTrue(service.current(user, 0, sid).isPresent());
    when(clock.instant()).thenReturn(now.plusSeconds(300));
    assertTrue(service.current(user, 0, sid).isEmpty());
    when(clock.instant()).thenReturn(now);
    when(identities.isCurrent(user, 0)).thenReturn(false);
    assertTrue(service.current(user, 0, sid).isEmpty());
  }

  @Test
  void replacesPendingChallengeAndRejectsTicketTampering() {
    when(proofs.current(user, 0, sid))
        .thenReturn(Optional.of(new MfaProofs.Proof("RECOVERY", now, now.plusSeconds(300))));
    service.begin(user, 0, sid, "First", parameters);
    String first = service.current(user, 0, sid).orElseThrow().ticket();
    service.begin(user, 0, sid, "Second", parameters);
    var second = service.current(user, 0, sid).orElseThrow();
    assertNotEquals(first, second.ticket());
    assertThrows(
        AuthorizationContinuation.Unavailable.class, () -> service.resume(first, user, 0, sid));
    assertEquals("PendingAuthorization[redacted]", pending.get().toString());
    service.clear();
    assertTrue(service.current(user, 0, sid).isEmpty());
  }

  @Test
  void rejectsUnboundedOrUnvalidatedParameters() {
    assertThrows(
        AuthorizationContinuation.Unavailable.class,
        () -> service.begin(null, 0, sid, "Client", parameters));
    assertThrows(
        AuthorizationContinuation.Unavailable.class,
        () -> service.begin(user, 0, null, "Client", parameters));
    assertThrows(
        AuthorizationContinuation.Unavailable.class,
        () -> service.begin(user, 0, sid, null, parameters));
    assertThrows(
        AuthorizationContinuation.Unavailable.class,
        () -> service.begin(user, 0, sid, "A".repeat(121), parameters));
    assertThrows(
        AuthorizationContinuation.Unavailable.class,
        () -> service.begin(user, 0, sid, "Client", null));
    for (String key :
        new String[] {
          "return_to",
          "redirect_uri",
          "state",
          "response_type",
          "code_challenge",
          "code_challenge_method"
        }) {
      var invalid = new HashMap<>(parameters);
      invalid.put(key, key.equals("redirect_uri") ? "A".repeat(1025) : "invalid");
      assertThrows(
          AuthorizationContinuation.Unavailable.class,
          () -> service.begin(user, 0, sid, "Client", invalid));
    }
    assertThrows(
        AuthorizationContinuation.Unavailable.class,
        () -> service.begin(user, 0, sid, "Client", Map.of("response_type", "code")));
  }
}

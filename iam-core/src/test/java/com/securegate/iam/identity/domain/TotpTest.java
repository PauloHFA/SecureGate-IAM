package com.securegate.iam.identity.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashSet;
import org.junit.jupiter.api.Test;

class TotpTest {
  // Public RFC 6238 Appendix B SHA1 test vector, never a production credential.
  // https://www.rfc-editor.org/rfc/rfc6238.html#appendix-B
  // The six-digit profile takes the last six digits. See docs/runbooks/gitguardian.md.
  private static final TotpSecret SECRET = TotpSecret.parse("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");

  @Test
  void matchesAllPublishedSha1VectorsIncludingBeyond2038() {
    long[] times = {59, 1111111109, 1111111111, 1234567890, 2000000000, 20000000000L};
    String[] codes = {"287082", "081804", "050471", "005924", "279037", "353130"};
    assertArrayEquals(
        "12345678901234567890".getBytes(StandardCharsets.US_ASCII), SECRET.keyBytes());
    for (int i = 0; i < times.length; i++) {
      assertEquals(
          times[i] / 30,
          Totp.match(SECRET, codes[i], Instant.ofEpochSecond(times[i]), -1).orElseThrow());
    }
  }

  @Test
  void permitsOnlyAdjacentTimeStepsAndReturnsCounterForAtomicConsumption() {
    assertEquals(1, Totp.match(SECRET, "287082", Instant.ofEpochSecond(29), -1).orElseThrow());
    assertEquals(1, Totp.match(SECRET, "287082", Instant.ofEpochSecond(60), -1).orElseThrow());
    assertTrue(Totp.match(SECRET, "287082", Instant.ofEpochSecond(90), -1).isEmpty());
    assertTrue(Totp.match(SECRET, "287082", Instant.ofEpochSecond(59), 1).isEmpty());
    assertTrue(Totp.match(SECRET, "287082", Instant.ofEpochSecond(59), 2).isEmpty());
    assertEquals(0, Totp.match(SECRET, "755224", Instant.EPOCH, -1).orElseThrow());
    assertTrue(Totp.match(SECRET, "755224", Instant.ofEpochSecond(60), -1).isEmpty());
  }

  @Test
  void rejectsMalformedCodesAndInvalidVerificationContext() {
    Instant now = Instant.ofEpochSecond(59);
    for (String code :
        new String[] {null, "", "28708", "2870820", " 287082", "２８７０８２", "abcdef", "000000"}) {
      assertTrue(Totp.match(SECRET, code, now, -1).isEmpty());
    }
    assertThrows(IllegalArgumentException.class, () -> Totp.match(null, "287082", now, -1));
    assertThrows(IllegalArgumentException.class, () -> Totp.match(SECRET, "287082", null, -1));
    assertThrows(IllegalArgumentException.class, () -> Totp.match(SECRET, "287082", now, -2));
    assertThrows(
        IllegalArgumentException.class,
        () -> Totp.match(SECRET, "287082", Instant.ofEpochSecond(-1), -1));
  }

  @Test
  void generatesCanonical160BitSecretsWithoutExposingThemInDiagnostics() {
    var values = new HashSet<String>();
    for (int i = 0; i < 100; i++) {
      TotpSecret secret = TotpSecret.generate();
      assertTrue(secret.encoded().matches("[A-Z2-7]{32}"));
      assertEquals(20, secret.keyBytes().length);
      assertEquals(secret.encoded(), TotpSecret.parse(secret.encoded()).encoded());
      values.add(secret.encoded());
      assertEquals("TotpSecret[redacted]", secret.toString());
    }
    assertEquals(100, values.size());
    for (String input :
        new String[] {
          null,
          "",
          "A".repeat(31),
          "A".repeat(33),
          "a".repeat(32),
          "0".repeat(32),
          SECRET.encoded() + "="
        }) {
      var failure = assertThrows(IllegalArgumentException.class, () -> TotpSecret.parse(input));
      assertEquals("Invalid TOTP secret.", failure.getMessage());
      assertNull(failure.getCause());
    }
  }
}

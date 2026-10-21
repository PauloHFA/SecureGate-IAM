package com.securegate.iam.audit.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.securegate.iam.audit.application.port.OutboxTraceParent;
import org.junit.jupiter.api.Test;

class OutboxTraceParentTest {
  @Test
  void acceptsOnlyBoundedVersionZeroNonzeroIdentifiersAndLocalFlags() {
    String value = "00-" + "1".repeat(32) + "-" + "2".repeat(16) + "-01";
    assertThat(OutboxTraceParent.safeOrNull(value)).isEqualTo(value);
    assertThat(OutboxTraceParent.safeOrNull(value.substring(0, 54) + "0")).endsWith("-00");
    for (String invalid :
        java.util.List.of(
            "",
            "secret\r\n",
            value + "x",
            value.replace("00-", "01-"),
            value.replace("1", "A"),
            value.replace("-01", "-ff"),
            "00-" + "0".repeat(32) + "-" + "2".repeat(16) + "-01",
            "x".repeat(2000))) {
      assertThat(OutboxTraceParent.safeOrNull(invalid)).isNull();
    }
    assertThat(OutboxTraceParent.safeOrNull(null)).isNull();
  }
}

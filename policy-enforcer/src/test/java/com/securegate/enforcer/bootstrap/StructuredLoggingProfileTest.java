package com.securegate.enforcer.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class StructuredLoggingProfileTest {
  @TempDir Path directory;

  @Test
  void activatesRealConsoleAndFileEncodersWithoutStartingInfrastructure(CapturedOutput output)
      throws Exception {
    Path logFile = directory.resolve("operations.jsonl");
    try {
      try (var context =
          new SpringApplicationBuilder(EmptyApplication.class, ConsumerTracingConfiguration.class)
              .web(WebApplicationType.NONE)
              .profiles("structured-logs", "consumer-tracing")
              .run(
                  "--logging.file.name=" + logFile.toAbsolutePath(),
                  "--spring.main.register-shutdown-hook=false")) {
        MDC.put("correlationId", "client-test");
        MDC.put("Authorization", "synthetic-mdc-credential");
        var tracer = context.getBean(io.micrometer.tracing.Tracer.class);
        var consumer = new com.securegate.enforcer.infrastructure.ConsumerTrace(tracer);
        consumer.consume(
            null,
            false,
            false,
            () -> {
              LoggerFactory.getLogger(getClass())
                  .error(
                      "synthetic-free-message {}",
                      "synthetic-token",
                      new RuntimeException("synthetic-exception-credential"));
              assertThat(MDC.get("traceId")).matches("[0-9a-f]{32}");
              return com.securegate.enforcer.infrastructure.RevocationMessageHandler.Result.ACK;
            });
        assertThat(MDC.get("traceId")).isNull();
        assertThat(context.getEnvironment().getProperty("logging.structured.format.console"))
            .isEqualTo(SafeOperationalLogFormatter.class.getName());
      }
      String console = output.getOut();
      String file = Files.readString(logFile);
      for (String captured : new String[] {console, file}) {
        assertThat(captured)
            .isNotBlank()
            .doesNotContain("synthetic-", "Authorization", "client-test");
        for (String line : captured.lines().filter(l -> !l.isBlank()).toList()) {
          var tree = new ObjectMapper().readTree(line);
          assertThat(tree.get("service").asText()).isEqualTo("policy-enforcer");
          assertThat(tree.size()).isEqualTo(7);
        }
        assertThat(captured).contains("correlationHash");
      }
    } finally {
      MDC.clear();
      var logging = LoggingSystem.get(getClass().getClassLoader());
      ((ch.qos.logback.classic.LoggerContext) LoggerFactory.getILoggerFactory()).reset();
      logging.cleanUp();
      System.clearProperty("LOG_FILE");
      System.clearProperty("CONSOLE_LOG_STRUCTURED_FORMAT");
      System.clearProperty("FILE_LOG_STRUCTURED_FORMAT");
      logging.beforeInitialize();
      logging.initialize(
          new org.springframework.boot.logging.LoggingInitializationContext(
              new org.springframework.core.env.StandardEnvironment()),
          null,
          null);
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class EmptyApplication {}
}

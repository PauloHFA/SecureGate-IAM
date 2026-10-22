package com.securegate.enforcer.bootstrap;

import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import java.net.URI;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Explicit local export; the existing provider retains sampling and instrumentation policy. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "securegate.tracing.export.enabled", havingValue = "true")
public class LocalOtlpExportConfiguration {
  @Bean(destroyMethod = "close")
  SpanProcessor localOtlpSpanProcessor(
      @Value("${management.tracing.enabled:false}") boolean tracingEnabled,
      @Value("${securegate.tracing.export.endpoint:http://127.0.0.1:14318/v1/traces}")
          String endpoint) {
    if (!tracingEnabled) {
      throw new IllegalArgumentException("OTLP export requires management.tracing.enabled=true");
    }
    validateEndpoint(endpoint);
    var exporter =
        OtlpHttpSpanExporter.builder()
            .setEndpoint(endpoint)
            .setConnectTimeout(Duration.ofMillis(500))
            .setTimeout(Duration.ofSeconds(1))
            .setRetryPolicy(null)
            .build();
    // Provider closes its processors first; the bean close is an idempotent fallback on startup
    // failure.
    return BatchSpanProcessor.builder(exporter)
        .setMaxQueueSize(512)
        .setMaxExportBatchSize(128)
        .setScheduleDelay(Duration.ofSeconds(1))
        .setExporterTimeout(Duration.ofSeconds(2))
        .build();
  }

  private static void validateEndpoint(String endpoint) {
    try {
      var uri = URI.create(endpoint);
      if ("http".equals(uri.getScheme())
          && "127.0.0.1".equals(uri.getHost())
          && uri.getPort() >= 1024
          && uri.getPort() <= 65535
          && "/v1/traces".equals(uri.getRawPath())
          && uri.getRawUserInfo() == null
          && uri.getRawQuery() == null
          && uri.getRawFragment() == null) {
        return;
      }
    } catch (IllegalArgumentException ignored) {
      // Do not include an operator-supplied URL (potential credentials) in the failure message.
    }
    throw new IllegalArgumentException(
        "OTLP endpoint must be http://127.0.0.1:<1024-65535>/v1/traces");
  }
}

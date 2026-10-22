package com.securegate.iam.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.tracing.Tracer;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class HttpTracingConfigurationTest {
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withBean(
              io.micrometer.core.instrument.MeterRegistry.class,
              io.micrometer.core.instrument.simple.SimpleMeterRegistry::new)
          .withBean(
              org.springframework.boot.actuate.autoconfigure.observation.ObservationProperties
                  .class,
              org.springframework.boot.actuate.autoconfigure.observation.ObservationProperties::new)
          .withUserConfiguration(HttpTracingConfiguration.class);

  @Test
  void defaultsToNoTracerProviderOrHandler() {
    runner.run(
        context -> {
          assertThat(context)
              .doesNotHaveBean(Tracer.class)
              .doesNotHaveBean(SdkTracerProvider.class)
              .doesNotHaveBean(SafeHttpServerTracingHandler.class);
        });
  }

  @Test
  void zeroSamplingOverridesSampledRemoteParent() {
    runner
        .withPropertyValues(
            "management.tracing.enabled=true", "management.tracing.sampling.probability=0")
        .run(
            context -> {
              var parent =
                  SpanContext.createFromRemoteParent(
                      "1".repeat(32),
                      "2".repeat(16),
                      TraceFlags.getSampled(),
                      TraceState.getDefault());
              var span =
                  context
                      .getBean(SdkTracerProvider.class)
                      .get("test")
                      .spanBuilder("test")
                      .setParent(Context.root().with(Span.wrap(parent)))
                      .startSpan();
              assertThat(span.getSpanContext().isSampled()).isFalse();
              span.end();
            });
  }

  @Test
  void invalidSamplingFailsStartup() {
    runner
        .withPropertyValues(
            "management.tracing.enabled=true", "management.tracing.sampling.probability=2")
        .run(context -> assertThat(context).hasFailed());
  }
}

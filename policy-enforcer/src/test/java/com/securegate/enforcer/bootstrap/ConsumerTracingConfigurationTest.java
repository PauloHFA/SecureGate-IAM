package com.securegate.enforcer.bootstrap;

import static org.assertj.core.api.Assertions.*;

import io.micrometer.tracing.Tracer;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ConsumerTracingConfigurationTest {
  final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(ConsumerTracingConfiguration.class);

  @Test
  void defaultsToNoProviderOrTracer() {
    runner.run(
        ctx ->
            assertThat(ctx).doesNotHaveBean(Tracer.class).doesNotHaveBean(SdkTracerProvider.class));
  }

  @Test
  void enablesExplicitProviderWithoutExporterOrWorker() {
    runner
        .withPropertyValues("management.tracing.enabled=true")
        .run(
            ctx -> {
              assertThat(ctx).hasSingleBean(Tracer.class).hasSingleBean(SdkTracerProvider.class);
              assertThat(ctx)
                  .doesNotHaveBean(
                      com.securegate.enforcer.infrastructure.RabbitRevocationWorker.class);
              assertThat(ctx.getBean(Tracer.class).currentSpan()).isNull();
            });
  }

  @Test
  void invalidSamplingFailsStartup() {
    runner
        .withPropertyValues(
            "management.tracing.enabled=true", "management.tracing.sampling.probability=2")
        .run(ctx -> assertThat(ctx).hasFailed());
  }
}

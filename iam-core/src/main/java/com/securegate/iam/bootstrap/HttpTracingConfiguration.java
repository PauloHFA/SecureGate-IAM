package com.securegate.iam.bootstrap;

import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelPropagator;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.micrometer.tracing.otel.bridge.Slf4JEventListener;
import io.micrometer.tracing.propagation.Propagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "management.tracing.enabled", havingValue = "true")
public class HttpTracingConfiguration {
  @Bean
  io.micrometer.core.instrument.observation.DefaultMeterObservationHandler
      tracingIndependentMetrics(
          io.micrometer.core.instrument.MeterRegistry registry,
          org.springframework.boot.actuate.autoconfigure.observation.ObservationProperties
              properties) {
    return properties.getLongTaskTimer().isEnabled()
        ? new io.micrometer.core.instrument.observation.DefaultMeterObservationHandler(registry)
        : new io.micrometer.core.instrument.observation.DefaultMeterObservationHandler(
            registry,
            io.micrometer.core.instrument.observation.DefaultMeterObservationHandler.IgnoredMeters
                .LONG_TASK_TIMER);
  }

  @Bean(destroyMethod = "close")
  SdkTracerProvider httpTracerProvider(Sampler sampler, ObjectProvider<SpanProcessor> processors) {
    var builder =
        SdkTracerProvider.builder()
            .setSampler(sampler)
            .setResource(
                io.opentelemetry.sdk.resources.Resource.create(
                    io.opentelemetry.api.common.Attributes.of(
                        io.opentelemetry.api.common.AttributeKey.stringKey("service.name"),
                        "iam-core")));
    processors.orderedStream().forEach(builder::addSpanProcessor);
    return builder.build();
  }

  @Bean
  OtelTracer.EventPublisher httpTracingEvents() {
    var listener = new Slf4JEventListener();
    return listener::onEvent;
  }

  @Bean
  Tracer httpTracer(SdkTracerProvider provider, OtelTracer.EventPublisher events) {
    return new OtelTracer(provider.get("securegate.http"), new OtelCurrentTraceContext(), events);
  }

  @Bean
  Propagator httpPropagator(ContextPropagators propagators, SdkTracerProvider provider) {
    return new OtelPropagator(propagators, provider.get("securegate.http"));
  }

  @Bean
  ContextPropagators restrictedHttpPropagators() {
    return ContextPropagators.create(new RestrictedHttpTracePropagator());
  }

  @Bean
  Sampler httpTraceSampler(
      @Value("${management.tracing.sampling.probability:0.1}") double probability) {
    // Do not let a remote sampled bit override local sampling policy.
    return Sampler.traceIdRatioBased(probability);
  }

  @Bean
  @Order(1000)
  SafeHttpServerTracingHandler safeHttpServerTracingHandler(Tracer tracer, Propagator propagator) {
    return new SafeHttpServerTracingHandler(tracer, propagator);
  }
}

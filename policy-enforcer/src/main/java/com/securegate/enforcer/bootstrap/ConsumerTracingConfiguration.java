package com.securegate.enforcer.bootstrap;

import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.micrometer.tracing.otel.bridge.Slf4JEventListener;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "management.tracing.enabled", havingValue = "true")
public class ConsumerTracingConfiguration {
  @Bean(destroyMethod = "close")
  SdkTracerProvider consumerTracerProvider(
      @Value("${management.tracing.sampling.probability:0.1}") double probability,
      ObjectProvider<SpanProcessor> processors) {
    var builder =
        SdkTracerProvider.builder()
            .setSampler(Sampler.traceIdRatioBased(probability))
            .setResource(
                Resource.create(
                    Attributes.of(AttributeKey.stringKey("service.name"), "policy-enforcer")));
    processors.orderedStream().forEach(builder::addSpanProcessor);
    return builder.build();
  }

  @Bean
  OtelTracer.EventPublisher consumerTracingEvents() {
    var listener = new Slf4JEventListener();
    return listener::onEvent;
  }

  @Bean
  Tracer consumerTracer(SdkTracerProvider provider, OtelTracer.EventPublisher events) {
    return new OtelTracer(
        provider.get("securegate.consumer"), new OtelCurrentTraceContext(), events);
  }
}

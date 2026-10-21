package com.securegate.iam.bootstrap;

import com.securegate.iam.shared.infrastructure.VaultHttpTransport;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.Nullable;

@Configuration(proxyBeanMethods = false)
public class VaultHttpTransportConfiguration {
  @Bean
  VaultHttpTransport vaultHttpTransport(@Nullable Tracer tracer, @Nullable Propagator propagator) {
    return new VaultHttpTransport(() -> tracer, () -> propagator);
  }
}

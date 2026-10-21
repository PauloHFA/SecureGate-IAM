package com.securegate.enforcer.bootstrap;

import com.securegate.enforcer.infrastructure.MetricsHttpServer;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "enforcer.metrics.enabled", havingValue = "true")
@EnableConfigurationProperties(MetricsConfiguration.Settings.class)
public class MetricsConfiguration {
  @ConfigurationProperties("enforcer.metrics")
  public record Settings(boolean enabled, int port, String tokenFile) {
    public Settings {
      if (enabled && (port < 1024 || port > 65535)) {
        throw new IllegalArgumentException("Invalid metrics port.");
      }
    }

    @Override
    public String toString() {
      return "MetricsSettings[port=" + port + ", credential=redacted]";
    }
  }

  @Bean(initMethod = "start", destroyMethod = "close")
  MetricsHttpServer metricsServer(PrometheusMeterRegistry registry, Settings settings) {
    return new MetricsHttpServer(registry, settings.port(), settings.tokenFile());
  }
}

package com.securegate.iam.bootstrap;

import com.securegate.iam.authorization.application.port.ResourceEnforcement;
import com.securegate.iam.authorization.infrastructure.RedisResourceEnforcement;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ResourceEnforcementProperties.class)
public class ResourceEnforcementConfiguration {
  @Bean
  ResourceEnforcement resourceEnforcement(
      ResourceEnforcementProperties settings,
      @Qualifier("enforcementRedisTemplate") ObjectProvider<StringRedisTemplate> templates,
      MeterRegistry meters) {
    return settings.enabled()
        ? new RedisResourceEnforcement(templates.getObject(), meters)
        : ResourceEnforcement.DISABLED;
  }

  @Bean
  @ConditionalOnProperty(name = "iam.resources.enforcement.enabled", havingValue = "true")
  LettuceConnectionFactory enforcementRedisConnectionFactory(
      ResourceEnforcementProperties settings) {
    var server = new RedisStandaloneConfiguration(settings.host(), settings.port());
    server.setPassword(settings.password());
    var timeout = Duration.ofMillis(settings.timeoutMillis());
    var client =
        LettuceClientConfiguration.builder()
            .commandTimeout(timeout)
            .shutdownTimeout(Duration.ofMillis(100))
            .clientOptions(
                ClientOptions.builder()
                    .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                    .socketOptions(SocketOptions.builder().connectTimeout(timeout).build())
                    .build())
            .build();
    return new LettuceConnectionFactory(server, client);
  }

  @Bean
  @ConditionalOnProperty(name = "iam.resources.enforcement.enabled", havingValue = "true")
  StringRedisTemplate enforcementRedisTemplate(
      @Qualifier("enforcementRedisConnectionFactory") LettuceConnectionFactory factory) {
    return new StringRedisTemplate(factory);
  }
}

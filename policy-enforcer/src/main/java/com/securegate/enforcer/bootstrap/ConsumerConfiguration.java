package com.securegate.enforcer.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.ConnectionFactory;
import com.securegate.enforcer.application.RevocationHandler;
import com.securegate.enforcer.infrastructure.RabbitRevocationWorker;
import com.securegate.enforcer.infrastructure.RedisRevocationStore;
import com.securegate.enforcer.infrastructure.RevocationMessageHandler;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "enforcer.consumer.enabled", havingValue = "true")
@EnableConfigurationProperties(ConsumerProperties.class)
public class ConsumerConfiguration {
  @Bean
  RedisRevocationStore revocationStore(StringRedisTemplate redis) {
    return new RedisRevocationStore(redis);
  }

  @Bean
  RevocationHandler revocationHandler(RedisRevocationStore store) {
    return new RevocationHandler(store);
  }

  @Bean
  RevocationMessageHandler revocationMessages(ObjectMapper json, RevocationHandler handler) {
    return new RevocationMessageHandler(json, handler);
  }

  @Bean(initMethod = "start", destroyMethod = "close")
  RabbitRevocationWorker revocationWorker(
      ConsumerProperties settings,
      ObjectMapper json,
      RevocationMessageHandler handler,
      MeterRegistry meters) {
    var factory = new ConnectionFactory();
    factory.setHost(settings.host());
    factory.setPort(settings.port());
    factory.setVirtualHost(settings.virtualHost());
    factory.setUsername(settings.username());
    factory.setPassword(settings.password());
    factory.setAutomaticRecoveryEnabled(false);
    factory.setConnectionTimeout(2000);
    factory.setHandshakeTimeout(2000);
    factory.setChannelRpcTimeout(2000);
    factory.setRequestedHeartbeat(10);
    factory.useNio();
    return new RabbitRevocationWorker(
        factory, json, handler, meters, settings.batchSize(), settings.pollMillis());
  }
}

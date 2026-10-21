package com.securegate.iam.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.ConnectionFactory;
import com.securegate.iam.audit.application.OutboxPublisher;
import com.securegate.iam.audit.application.port.OutboxDelivery;
import com.securegate.iam.audit.infrastructure.JdbcOutboxDelivery;
import com.securegate.iam.audit.infrastructure.MeteredOutboxDelivery;
import com.securegate.iam.audit.infrastructure.OutboxWorker;
import com.securegate.iam.audit.infrastructure.RabbitEventTransport;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "iam.outbox.publisher.enabled", havingValue = "true")
@EnableConfigurationProperties(OutboxPublisherProperties.class)
public class OutboxPublisherConfiguration {
  @Bean
  OutboxDelivery outboxDelivery(
      JdbcTemplate jdbc, PlatformTransactionManager manager, MeterRegistry meters) {
    return new MeteredOutboxDelivery(new JdbcOutboxDelivery(jdbc, manager), meters);
  }

  @Bean(destroyMethod = "close")
  RabbitEventTransport eventTransport(
      ObjectMapper json, OutboxPublisherProperties settings, @Nullable Tracer tracer) {
    var factory = new ConnectionFactory();
    factory.setHost(settings.host());
    factory.setPort(settings.port());
    factory.setVirtualHost(settings.virtualHost());
    factory.setUsername(settings.username());
    factory.setPassword(settings.password());
    factory.setAutomaticRecoveryEnabled(false);
    factory.setConnectionTimeout(settings.timeoutMillis());
    factory.setHandshakeTimeout(settings.timeoutMillis());
    factory.setChannelRpcTimeout(settings.timeoutMillis());
    factory.setRequestedHeartbeat(10);
    factory.useNio();
    return new RabbitEventTransport(factory, json, settings.timeoutMillis(), tracer);
  }

  @Bean
  OutboxPublisher outboxPublisher(
      OutboxDelivery outbox, RabbitEventTransport transport, OutboxPublisherProperties settings) {
    return new OutboxPublisher(outbox, transport, settings.leaseSeconds());
  }

  @Bean(initMethod = "start", destroyMethod = "close")
  OutboxWorker outboxWorker(OutboxPublisher publisher, OutboxPublisherProperties settings) {
    return new OutboxWorker(publisher, settings.batchSize(), settings.pollMillis());
  }
}

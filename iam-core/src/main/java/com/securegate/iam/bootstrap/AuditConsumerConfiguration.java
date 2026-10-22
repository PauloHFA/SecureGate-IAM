package com.securegate.iam.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.ConnectionFactory;
import com.securegate.iam.audit.application.port.AuditEventStore;
import com.securegate.iam.audit.infrastructure.AuditConsumerTrace;
import com.securegate.iam.audit.infrastructure.RabbitAuditWorker;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.Nullable;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "iam.audit.consumer.enabled", havingValue = "true")
@EnableConfigurationProperties(AuditConsumerProperties.class)
public class AuditConsumerConfiguration {
  @Bean(initMethod = "start", destroyMethod = "close")
  RabbitAuditWorker auditConsumer(
      AuditConsumerProperties settings,
      ObjectMapper json,
      AuditEventStore store,
      MeterRegistry meters,
      @Nullable Tracer tracer) {
    // Requiring the store bean makes missing dedicated ingestion configuration fail at startup.
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
    return new RabbitAuditWorker(
        factory,
        json,
        store,
        meters,
        settings.batchSize(),
        settings.pollMillis(),
        new AuditConsumerTrace(tracer));
  }
}

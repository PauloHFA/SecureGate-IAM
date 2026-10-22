package com.securegate.iam.bootstrap;

import com.securegate.iam.shared.infrastructure.VaultDatabaseDataSource;
import com.securegate.iam.shared.infrastructure.VaultHttpTransport;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.nio.file.Path;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "iam.database.vault.enabled", havingValue = "true")
public class VaultDatabaseConfiguration {
  @Bean(destroyMethod = "close")
  @Primary
  VaultDatabaseDataSource dataSource(
      Environment environment, MeterRegistry metrics, VaultHttpTransport transport) {
    if (environment.getProperty("spring.flyway.enabled", Boolean.class, true)) {
      throw new IllegalStateException("Dynamic database mode requires separate migrations.");
    }
    return new VaultDatabaseDataSource(
        environment.getRequiredProperty("spring.datasource.url"),
        URI.create(environment.getProperty("IAM_VAULT_ADDRESS", "http://127.0.0.1:8200")),
        Path.of(
            environment.getProperty("IAM_VAULT_ROLE_ID_FILE", "infra/.local/iam-runtime-role-id")),
        Path.of(
            environment.getProperty(
                "IAM_VAULT_SECRET_ID_FILE", "infra/.local/iam-runtime-secret-id")),
        metrics,
        transport);
  }
}

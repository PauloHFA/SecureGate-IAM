# Coleta local do IAM — 10B2a

Compose separado da infraestrutura de negócio: Prometheus 3.15.0 e Grafana 13.2.4 fixados por versão/digest, somente loopback, credenciais por arquivos privados e dashboard provisionado. Windows/Docker Desktop é a plataforma validada.

Seguir o [runbook](../../docs/runbooks/coleta-iam-local.md) para preparação/startup/login/parada e o [inventário](../../docs/OBSERVABILIDADE.md) para interpretar séries/limites. Não existe senha padrão admin/admin nem token administrativo no scrape. O [teste opt-in](../../integration-tests/src/test/java/com/securegate/e2e/ObservabilitySmoke.java) exerce estes arquivos contra um IAM real.

A [coleta opcional do enforcer](../../docs/runbooks/metricas-enforcer-local.md) está na 10B2b1, com overlay/token/dashboard próprios e migração com backups. A [10B2b2a](../../docs/runbooks/metricas-rabbitmq-local.md) acrescenta coleta RabbitMQ opt-in e dashboard separado de seis painéis. A [10B2b2b](../../docs/runbooks/metricas-postgres-local.md) acrescenta exporter PostgreSQL com login/senha próprios e seis painéis. A [10B2b2c](../../docs/runbooks/metricas-redis-local.md) acrescenta exporter Redis com ACL sem acesso a chaves e seis painéis. Vault fica para 10B2b2d; logs/tracing para 10B3. Não representa coleta sustentada ou aceite de capacidade.

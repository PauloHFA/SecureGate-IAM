# Coleta local do IAM — 10B2a

Compose separado da infraestrutura de negócio: Prometheus 3.15.0 e Grafana 13.2.4 fixados por versão/digest, somente loopback, credenciais por arquivos privados e dashboard provisionado. Windows/Docker Desktop é a plataforma validada.

Seguir o [runbook](../../docs/runbooks/coleta-iam-local.md) para preparação/startup/login/parada e o [inventário](../../docs/OBSERVABILIDADE.md) para interpretar séries/limites. Não existe senha padrão admin/admin nem token administrativo no scrape. O [teste opt-in](../../integration-tests/src/test/java/com/securegate/e2e/ObservabilitySmoke.java) exerce estes arquivos contra um IAM real.

A [coleta opcional do enforcer](../../docs/runbooks/metricas-enforcer-local.md) está na 10B2b1, com overlay/token/dashboard próprios e migração com backups. A [10B2b2a](../../docs/runbooks/metricas-rabbitmq-local.md) acrescenta coleta RabbitMQ opt-in e dashboard separado de seis painéis. A [10B2b2b](../../docs/runbooks/metricas-postgres-local.md) acrescenta exporter PostgreSQL com login/senha próprios e seis painéis. A [10B2b2c](../../docs/runbooks/metricas-redis-local.md) acrescenta exporter Redis com ACL sem acesso a chaves e seis painéis. A [10B2b2d](../../docs/runbooks/metricas-vault-local.md) acrescenta Vault com listener publicado autenticado e token por arquivo de 1h. O overlay do servidor exige reset do Vault dev; não foi aplicado à stack persistente. A matriz de correlação está no mesmo runbook; logs/tracing para 10B3. Não representa coleta sustentada ou aceite de capacidade.

## Backend local opcional — 10B4a

O overlay docker-compose.tracing.yml acrescenta Tempo 3.1.1 por digest e datasource Grafana com Start-Observability.ps1 -IncludeTracing. Preserva Prometheus padrão/credenciais, usa volume próprio e portas loopback. Tempo não autentica portas diretas; apenas o proxy Grafana usa o acesso existente. Sem exporter IAM/enforcer nesta entrega. [Operação e reversão](../../docs/runbooks/backend-tracing-local.md), [decisão](../../docs/adr/064-backend-local-de-tracing.md), [evidências](../../docs/evidencias/etapa-10b4a.md). Não executar limpeza de volumes reais para retirar o overlay.

## Continuação 10B4b

Exportação OTLP opcional implementada no IAM/enforcer, sem alterar a stack persistente. Ativação, destino local, fila/timeouts, descarte em falha e reversão: [guia de exportação](../../docs/runbooks/exportacao-otlp-local.md). A validação integrada/sustentada continua na 10B4c; as limitações de entrega anteriores acima são históricas.

# Coleta local do IAM — 10B2a

Compose separado da infraestrutura de negócio: Prometheus 3.15.0 e Grafana 13.2.4 fixados por versão/digest, somente loopback, credenciais por arquivos privados e dashboard provisionado. Windows/Docker Desktop é a plataforma validada.

Seguir o [runbook](../../docs/runbooks/coleta-iam-local.md) para preparação/startup/login/parada e o [inventário](../../docs/OBSERVABILIDADE.md) para interpretar séries/limites. Não existe senha padrão admin/admin nem token administrativo no scrape. O [teste opt-in](../../integration-tests/src/test/java/com/securegate/e2e/ObservabilitySmoke.java) exerce estes arquivos contra um IAM real.

A coleta do enforcer e os exporters das dependências ficam para 10B2b; logs/tracing para 10B3. Não representa coleta sustentada ou aceite de capacidade.

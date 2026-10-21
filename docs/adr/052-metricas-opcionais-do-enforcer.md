# ADR-052 — Métricas opcionais do enforcer

- Data: 21/10/2026
- Estado: aceita para implementação local 10B2b1

## Contexto

O enforcer já emite meters de processamento, mas é um processo Spring non-web. Coleta das dependências exige exporters/permissões próprios e não deve ser confundida com os contadores internos. A 10B2a já oferece Prometheus/Grafana para IAM.

## Decisão

Dividir 10B2b em 10B2b1 (enforcer) e 10B2b2 (dependências). Manter web-application-type=none e acrescentar listener JDK HTTP opcional, bind fixo 127.0.0.1, GET /metrics e token dedicado por arquivo/digest. Não adicionar MVC/Actuator público nem mudar consumidores/projeções. Registro Prometheus na versão do BOM, tag application fixa; sem IDs/e-mails/eventos/credenciais como tags.

Dois workers com fila/backlog limitados, parada explícita e respostas genéricas. Token inválido/duplicado recusado; query/métodos/caminhos extras não aceitos. Default sem listener. Launcher opcional e gerador com Target Enforcer preservando default IAM. Job/overlay de coleta opt-in, dashboard separado de seis painéis e migração explícita de configuração antiga com backups, sem recriar senha/volumes.

Testes do módulo verificam segurança/lifecycle/default e regressão de consumo. Smoke real exige API→Outbox→RabbitMQ→projeção Redis/ACK→Prometheus/Grafana, além de token errado isolado do IAM e recuperação. Não alterar schema V22, grants SQL ou ACL Vault/Redis/RabbitMQ.

## Consequências

Enforcer continua non-web por padrão, com superfície mínima só quando solicitada. Loopback/arquivo privado não é desenho de coleta remota; proteção de arquivos e reinício na rotação permanecem necessários. Up não é saúde de consumidor; contadores sem evento podem estar ausentes. Não há endpoint de readiness, TLS, rate limit, tracing ou medição de saturação. Exporters das dependências e observabilidade completa permanecem pendentes.

[Operação](../runbooks/metricas-enforcer-local.md), [inventário](../OBSERVABILIDADE.md), [evidências](../evidencias/etapa-10b2b1.md).

Referências primárias: [HttpServer JDK 21](https://docs.oracle.com/en/java/javase/21/docs/api/jdk.httpserver/com/sun/net/httpserver/HttpServer.html) e [Prometheus/Micrometer](https://docs.micrometer.io/micrometer/reference/1.15/implementations/prometheus). A autorização, porta e limites são decisões locais verificadas nos testes.

# Evidências — 10B2b2a: coleta RabbitMQ

- Data: 21/10/2026, America/Sao_Paulo.
- Branch: feat/rabbitmq-prometheus-metrics, base 7b92e4f.
- Estado: implementação e validação locais concluídas; incluída neste commit; push/CI remota pendentes.
- Escopo: plugin nativo opcional, overlay loopback, scrape agregado, migração com backup e dashboard de seis painéis.

## Validação

Primeira execução:

```powershell
.\mvnw.cmd -B -ntp -pl integration-tests -am spotless:apply '-Dtest=NoSelectedUnitTests' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=RabbitMqObservabilitySmoke' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djacoco.skip=true' verify
```

**BUILD SUCCESS às 13:09:35 UTC−03**, duração **2 min e 4 s**, **1 teste** (110,0 s), zero falhas/erros/ignorados; exit code nativo 0. Log ignorado .firecrawl/etapa10b2b2a-smoke.log. Spotless/enforcer de ambiente aprovados; a seleção não executa a suíte padrão e desabilita JaCoCo.

Seleção final, incluindo regressão da coleta IAM:

```powershell
.\mvnw.cmd -B -ntp -pl integration-tests -am spotless:apply '-Dtest=NoSelectedUnitTests' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=RabbitMqObservabilitySmoke,ObservabilitySmoke' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djacoco.skip=true' verify
```

**BUILD SUCCESS às 13:14:08 UTC−03**, duração **3 min e 4 s**, **2 testes**, zero falhas/erros/ignorados; exit code nativo 0. RabbitMQ: 94,10 s; regressão IAM: 77,86 s. Log ignorado .firecrawl/etapa10b2b2a-final.log. Esta versão inclui contagens com cor neutra, conferência de todas as portas mescladas em loopback e comparação de artefatos com Basic Grafana. As seleções se sobrepõem: não somar reexecuções como testes distintos.

Checks adicionais: parser PowerShell aprovado, JSON com seis painéis/seis expressões, links locais e referências do guia revisados, comparação em memória dos arquivos alterados com cinco valores privados de .env sem ocorrências e git diff --check aprovado. A suíte padrão/arquitetura/cobertura completa não foi repetida; Java de produção não foi alterado.

| Contrato | Verificação |
| --- | --- |
| Broker real | Imagem RabbitMQ 4.3.6 e Compose/overlay/configuração/topologia versionados, copiados para workspace descartável; não usa exporter simulado. |
| Ativação opcional | Compose base sem publicação de métricas; overlay acrescenta listener sem alterar portas AMQP/management ou configurações base. |
| Migração | Colisão de porta de coleta recusada sem escrita, backup igual ao original, duplicidade recusada, senha e compose.env preservados. |
| Origem dos gauges | Duas mensagens publicadas com confirmação AMQP; uma recebida sem ACK, outra pronta; contadores reais no Prometheus. |
| Confirmação | Após ACK, unacked retorna a zero. |
| Agregação | Nome aleatório da fila exclusiva ausente no scrape /metrics. |
| Dashboard | Seis painéis/expressões retornam dados reais; frame via datasource Grafana e acesso anônimo 401. |
| Falha/recuperação | Pausa real do broker leva a up=0; unpause recupera up=1. |
| Escopo da fixture nova | IAM/enforcer não inicializados; up IAM=0 é esperado neste smoke de dependência. Regressão IAM usa a fixture anterior separadamente. |
| Artefatos | Configuração/logs não privados comparados em memória com credenciais geradas; nenhum segredo impresso. |
| Limpeza | Somente projetos gerados pelo teste são removidos com volumes; .env/definições/token/senha de teste apagados no finally. |

Não houve reconfiguração da stack persistente nem alteração de .env do usuário. Resultados anteriores (10B1/10B2a/10B2b1) são históricos, não novos builds completos desta entrega.

## Limites e próxima entrega

Não altera Java de produção, schema V22, ACL, grants ou topologia de negócio. Endpoint local sem autenticação; isolamento de host/rede Docker conforme [ADR-053](../adr/053-coleta-opcional-rabbitmq.md). Smoke mede contrato funcional, sem capacidade/SLA; não inicializa IAM/enforcer. Full reactor e cobertura completa não são comprovados pela seleção opt-in.

Próxima: 10B2b2b — PostgreSQL; depois 10B2b2c — Redis e 10B2b2d — Vault. Logs/tracing 10B3 e 10C–10D continuam pendentes.

[Passo a passo](../GUIA-DE-IMPLEMENTACAO.md), [operação](../runbooks/metricas-rabbitmq-local.md), [inventário](../OBSERVABILIDADE.md).

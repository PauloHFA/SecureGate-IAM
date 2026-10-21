# Evidências — Etapa 10B2b1: métricas/coleta do enforcer

- Data: 21/10/2026; horários UTC−03.
- Base: 123c4b0 (10B2a); branch feat/enforcer-prometheus-metrics; diretório limpo ao iniciar.
- Estado: implementação local, incluída neste commit; push/CI remota pendentes.

## Entrega e divisão

10B2b dividida em 10B2b1 (enforcer) e 10B2b2 (exporters de dependências). Listener JDK opcional em 127.0.0.1, GET /metrics, token próprio por arquivo/digest, duas threads/fila 16/backlog 16, encerramento explícito. Spring permanece non-web; default sem listener. Registry Prometheus gerenciado pelo BOM e tag application fixa; sem alteração de consumidores/projeções, schema V22, grants SQL ou ACL de infraestrutura.

Gerador preserva default IAM e aceita Target Enforcer; launcher ganhou EnableMetrics/MetricsPort. Coleta nova por IncludeEnforcer, job/overlay opcionais e dashboard separado de seis painéis. Migração explícita de configuração 10B2a com backups, sem regenerar senha Grafana ou remover volumes. Runtime IAM/fixture existente não alterados. Exporters, logs/tracing e medição sustentada permanecem pendentes.

## Build do enforcer e dependência

```powershell
.\mvnw.cmd -B -ntp -pl policy-enforcer -am spotless:apply verify
```

**BUILD SUCCESS às 12:27:35**, duração **1 min e 2 s**: **62 testes do enforcer** (40 unitários/arquitetura + 22 integrações), mais **43 testes de security-events**, zero falhas/erros/ignorados. Spotless, arquitetura e limites JaCoCo do enforcer aprovados. Log ignorado .firecrawl/etapa10b2b1-enforcer.log.

A primeira compilação dos testes acusou uso de PrometheusMeterRegistry como recurso AutoCloseable; ajustado o teste para fechamento explícito, sem mudar a aplicação para contornar. O build repetido acima passou.

Testes novos comprovam: GET autorizado e formato, 401 para ausência/incorreção/duplicidade, 404 para caminho extra, 400 para query e 405 para escrita; no-store/sem cookie; rejeição de arquivo ausente/malformado/grande/controles sem caminho/cause; digest carregado uma vez; listener fechado após close. Integrações novas confirmam default sem listener/web context e registro JVM real quando habilitado, ainda sem servlet. Integrações existentes de consumidores/Redis/Vault continuaram aprovadas.

## Smokes reais e regressão IAM

```powershell
.\mvnw.cmd -B -ntp -pl integration-tests -am spotless:apply '-Dtest=NoSelectedUnitTests' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=EnforcerObservabilitySmoke,ObservabilitySmoke' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djacoco.skip=true' verify
```

**BUILD SUCCESS às 12:31:20**, duração **2 min e 58 s**, **2 testes**, zero falhas/erros/ignorados: EnforcerObservabilitySmoke 93,28 s e regressão ObservabilitySmoke IAM-only 72,71 s. Esta última preserva 12 painéis/14 expressões, frame Grafana e controle de token sem exigir arquivo enforcer.

Foi acrescentada exigência explícita de fato canônico publicado/processado e repetido somente o smoke afetado:

```powershell
.\mvnw.cmd -B -ntp -pl integration-tests -am spotless:apply '-Dtest=NoSelectedUnitTests' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=EnforcerObservabilitySmoke' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djacoco.skip=true' verify
```

**BUILD SUCCESS às 12:33:28**, duração **1 min e 33 s**, **1 teste** (81,26 s), zero falhas/erros/ignorados. Logs ignorados .firecrawl/etapa10b2b1-smoke.log e .firecrawl/etapa10b2b1-smoke-final.log. Exit codes nativos preservados no PowerShell com exit $LASTEXITCODE. As seleções se sobrepõem; não somar reexecuções como testes distintos do build padrão.

| Contrato | Resultado final |
| --- | --- |
| Processo real | JAR enforcer com listener e consumidores, IAM/JAR e PostgreSQL/Redis/RabbitMQ/Vault descartáveis; nenhum endpoint falso de métricas. |
| Autenticação cruzada | Token IAM recebeu 401 em /metrics do enforcer. |
| Produção por API | Usuário criado e bloqueado por API administrativa; nenhum contador/Redis/envelope fabricado no smoke. |
| Cadeia canônica | user.locked com published_at na Outbox e marcador de processamento block-policy no Redis; projeção identity termina em LOCKED. |
| Coleta | up IAM e enforcer =1; JVM do enforcer e contador ACK real recebidos pelo Prometheus. |
| Grafana | Dashboard enforcer provisionado com seis painéis/sete expressões; todas aceitas por Prometheus; frame real de ACK via datasource. API anônima recebe 401. |
| Controle | Token do scraper enforcer inválido: up=0 e 401 no alvo; IAM permanece up=1; restauração do original recupera enforcer. |
| Artefatos | Busca fora da pasta privada por tokens, senha/Basic Grafana, senha infraestrutura da fixture e dados do usuário sintético: zero correspondências. |
| Limpeza | Projeto Compose aleatório e seus volumes removidos; três arquivos privados removidos em finally. |

Plataforma Windows/PowerShell 5.1, Java 21, Docker Desktop/Compose 5.6.0; Prometheus/Grafana mantêm versões/digests da 10B2a. Arquivos versionados/scripts copiados para target, sem stack persistente ou .env na fixture. Resultados/logs em integration-tests/target/enforcer-observability-smoke/<execução>, ignorados. Contadores de revogação não foram produzidos nesse novo smoke; o evento exercitado é de identidade/bloqueio. Não houve QA visual do navegador.

## Scripts e migração

Em cópias isoladas sob .firecrawl: gerador criou tokens IAM/enforcer distintos de 64 hex e recusou sobrescrita preservando conteúdo; arquivos privados removidos. Migração recusou colisão de porta e repetição, preservou senha Grafana e cópia original do YAML nos backups, acrescentando somente job/flag. Não executou serviços persistentes. Migração é sequencial entre arquivos, sem rollback automático; usar coletores parados e revisar backups em falha parcial.

Parsers dos cinco scripts PowerShell e JSON do dashboard aprovados, git diff --check sem erros. Conferidos 1175 links Markdown locais válidos e 53 registros de etapas referenciados no guia. Comparação em memória dos 28 arquivos alterados com cinco valores sensíveis locais: zero correspondências, sem imprimir valores. Consulta Docker após os smokes não encontrou containers securegate-obs-test remanescentes.

## Limites e próxima entrega

Não foi repetido clean verify do reactor completo. Os 782 testes e a ressalva JVM/Hikari são históricos da [10B1](etapa-10b1.md), sem declaração de reexecução/resolução. O módulo afetado teve build completo próprio e houve regressão real de coleta IAM; JaCoCo desativado apenas nas seleções de smokes.

Up é sucesso de scrape, não saúde do consumidor. Métricas de mensagens só surgem após eventos; rate/p95 precisam de histórico. Sem endpoint health, TLS, rate limit, teste de saturação, coleta nativa de dependências, tracing/logs estruturados ou aceite de capacidade/SLA. Listener somente loopback; plataforma Linux/imagem reduzida jlink e coleta remota não foram validadas. Credencial requer reinício para rotação, diretórios ignorados exigem permissões e interrupção abrupta pode deixar recursos/arquivos de teste.

Próxima implementação: **10B2b2 — exporters das dependências**; depois 10B3 logs/tracing. Publicação/CI/merge e aceite global da etapa 10 continuam pendentes.

Referências: [operação](../runbooks/metricas-enforcer-local.md), [ADR-052](../adr/052-metricas-opcionais-do-enforcer.md), [inventário](../OBSERVABILIDADE.md), [passo a passo](../GUIA-DE-IMPLEMENTACAO.md).

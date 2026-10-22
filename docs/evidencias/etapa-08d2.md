# Evidências — 8D2: auditoria em escala

- Data: 20/10/2026.
- Branch: `perf/audit-scale-measurements`, base local `d7311d0` (8D1).
- Commit/publicação/CI remota desta entrega: pendentes.

## Método e resultados

Execução de 20/10/2026, das 00:00:26 às 00:11:30 (UTC−03); Maven terminou às 00:11:36, em 11 min e 15 s. A validação inicial com 30 mil eventos também passou. A massa principal gravou **2.000.000 eventos**; as três fases comparativas acrescentaram 30.000, confirmando **2.030.000 eventos e 2.030.000 recibos**, sem duplicatas ou erros.

PostgreSQL 16.15 descartável, limite de 4 CPUs/4 GiB; Java 21.0.8 em Windows amd64, heap máximo de 1 GiB. Docker Desktop 29.9.1 com 16 CPUs lógicas e aproximadamente 15,6 GiB disponíveis ao engine, host compartilhado. Configurações registradas: shared_buffers 128 MiB, work_mem 4 MiB, fsync/synchronous_commit on, pg_stat_statements.track top e track_io_timing on. Logins/pool, migrações V1–V16, índices, triggers e recibos são os reais da aplicação. Relações de auditoria medidas: 2.166.022.144 bytes (~2,02 GiB), incluindo índices/TOAST, sem WAL.

### Ingestão pelo store JDBC

Latências são **por lote**, incluindo geração/serialização/JDBC/commit; vazão usa tempo de parede. A fase principal tem dois produtores; as comparativas têm um. Os percentis são amostras locais, não SLA.

| Fase | Eventos | Lote | Produtores | Tempo (s) | Eventos/s | p50 lote (ms) | p95 lote (ms) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Massa principal | 2.000.000 | 100 | 2 | 583,14 | 3.429,71 | 50,25 | 79,83 |
| Comparação lote 1 | 10.000 | 1 | 1 | 47,28 | 211,53 | 3,40 | 8,74 |
| Comparação lote 20 | 10.000 | 20 | 1 | 9,29 | 1.076,91 | 17,44 | 25,67 |
| Comparação lote 100 | 10.000 | 100 | 1 | 8,00 | 1.249,93 | 74,42 | 115,80 |

A comparação de um produtor indica custo relevante por commit para lote 1. Lote 100 aumenta vazão e também a duração por lote em relação a 20. Concorrência, crescimento de índices e atividade do host diferem entre fases; não se deduz escalabilidade linear nem vazão máxima.

### Consultas e planos

Cinco aquecimentos e 30 amostras por cenário, limite 100, janela outubro/2026. A latência inclui transação read-only e decodificação do envelope pelo adaptador. Até 101 linhas são buscadas por causa do lookahead; filtros mais seletivos retornaram menos.

| Filtro | Linhas buscadas | p50 adaptador (ms) | p95 adaptador (ms) | Execução EXPLAIN (ms) | Plano registrado |
| --- | ---: | ---: | ---: | ---: | --- |
| Tempo | 101 | 8,97 | 17,63 | 0,401 | Index Scan em tempo/id |
| Tipo | 101 | 6,39 | 10,00 | 0,287 | Index Scan em tipo/tempo/id |
| Recurso | 101 | 5,74 | 9,59 | 0,498 | Index Scan em recurso/tempo/id |
| Ator | 68 | 4,17 | 7,56 | 0,445 | Bitmap Index/Heap Scan por ator + Sort |
| Correlação | 1 | 3,36 | 5,84 | 0,081 | Index Scan em correlação/tempo/id |
| Combinado | 68 | 3,14 | 4,14 | 0,296 | Bitmap Index/Heap Scan por ator + filtros e Sort |

Os planos acessaram somente `event_log_202610` nessa janela, com índices existentes; não foi forçado tipo de scan. O Sort de 68 linhas nos cenários de ator/combinado não foi removido artificialmente. EXPLAIN registra uma chamada parametrizada, não garante o mesmo plano genérico cacheado em toda execução.

### N+1 e paginação profunda

`pg_stat_statements` confirmou **1 SELECT** do login restrito para cada página de **1, 20 e 100 itens**; o serviço buscou 2, 21 e 101 linhas, respectivamente. Comandos transacionais e leituras de diagnóstico do proprietário não são buscas de dados do serviço. Não se afirma ausência de N+1 em outros contextos/endpoints.

Comparação de continuação equivalente com **OFFSET 200.000**, 10 amostras, mesmos 101 IDs e projeção/mapper SQL em ambas as variantes:

| Variante | p50 SQL/transferência (ms) | p95 (ms) | Execução EXPLAIN (ms) | Linhas no Index Scan | Shared Hit Blocks |
| --- | ---: | ---: | ---: | ---: | ---: |
| Cursor por tupla | 4,06 | 17,47 | 1,239 | 101 | 105 |
| OFFSET | 453,65 | 637,86 | 665,128 | 200.101 | 201.440 |

Ambos os planos tiveram zero Shared Read Blocks: cache aquecido. A obtenção prévia do anchor foi excluída da continuação por cursor; isto não mede salto aleatório gratuito para uma página arbitrária. Os percentis dessa tabela não incluem decodificação do envelope ou HTTP.

### Artefatos

Relatórios originais preservados em `.firecrawl/etapa8d2-benchmark-reports/`: `summary.json`, `samples.csv`, `plans/*.json` e relatório JUnit do benchmark. Cópia operacional em `iam-core/target/audit-benchmark/`. Logs: `.firecrawl/etapa8d2-smoke.log` e `.firecrawl/etapa8d2-benchmark.log`. São arquivos locais ignorados pelo Git; a síntese acima é versionada. Nenhuma credencial ou massa de dados é versionada.

## Validação

`mvnw.cmd -B -ntp clean verify` aprovado: **598 testes, zero falhas, zero erros e zero ignorados**, incluindo os oito casos ponta a ponta anteriores. Concluído em 20/10/2026 às 00:19:07 (UTC−03), em 5 min e 39 s. Spotless, ArchUnit e cobertura mínima JaCoCo de 80% dos pacotes configurados aprovados nos dois serviços. O benchmark optativo adicional passou como um teste separado; não foi descoberto/executado pelo build padrão.

Regressão em cópia isolada, com 337 arquivos de código/configuração/scripts idênticos por SHA-256. Log `.firecrawl/etapa8d2-verify.log`; relatórios em `.firecrawl/etapa8d2-reports/`. JARs e CSVs ponta a ponta atualizados nos targets locais. Script e exemplos PowerShell sem erros de parsing, 615 links Markdown locais válidos, `git diff --check` aprovado e nenhuma correspondência com credenciais locais nas alterações.

## Limites

Dados sintéticos pequenos e IDs sequenciais, cache aquecido e contêiner no host compartilhado. Não são resultados de carga HTTP/RabbitMQ, tráfego misto, payload máximo ou SLA de produção. Benchmark optativo separado do build normal. A stack persistente não é usada. Não há alteração de schema ou de parâmetros produtivos nesta entrega; JaCoCo é verificado no build completo separado.

Referências: [ADR-033](../adr/033-medicao-auditoria-em-escala.md), [guia](../runbooks/medicao-auditoria-local.md) e [plano](../PLANO-DE-IMPLEMENTACAO.md).

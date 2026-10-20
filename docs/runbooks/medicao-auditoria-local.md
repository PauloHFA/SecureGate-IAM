# Medição de auditoria em escala — 8D2

## Executar

Requisitos: JDK 21, Docker disponível, Maven Wrapper e espaço para o banco descartável e relatórios. O benchmark limita PostgreSQL a quatro CPUs/4 GiB e o JVM de teste a 1 GiB. O volume pode ocupar alguns GiB enquanto roda; o contêiner é removido ao terminar.

Na raiz:

```powershell
# Validação menor do harness; não comprova comportamento com milhões.
.\infra\scripts\Measure-Audit.ps1 -Rows 30000

# Medição padrão: 2 milhões + 30 mil amostras de comparação de lotes.
.\infra\scripts\Measure-Audit.ps1
```

`-Rows` permite 3.000–5.000.000. A execução pode durar vários minutos e não usa `.env`/stack persistente. O script seleciona somente `AuditPerformanceBenchmark`; a classe não roda no `mvnw verify` normal. `AUDIT_BENCHMARK_PROGRESS` mostra progresso a cada 100 mil eventos. Falha invalida a execução completa; não publique um resultado parcial como medição final.

## Preservar os resultados

Arquivos em `iam-core/target/audit-benchmark/`:

- `summary.json`: ambiente, configurações PostgreSQL, contagens, fases de ingestão, percentis, vazão, consultas, SELECTs por página, planos e espaço por relação.
- `samples.csv`: nanossegundos por lote/consulta, fase e tamanho. Percentis de ingestão são por lote, não por evento.
- `plans/*.json`: EXPLAIN ANALYZE/BUFFERS/SETTINGS das seis variantes e da paginação profunda.

Preserve antes de executar `clean`. Exemplo de cópia local ignorada pelo Git:

```powershell
$auditRunPath = Join-Path $PWD ('.firecrawl/audit-benchmark-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Force -Path $auditRunPath | Out-Null
Copy-Item -Path '.\iam-core\target\audit-benchmark\*' -Destination $auditRunPath -Recurse
```

Nenhum payload ou senha deve ser incluído no relatório. Os dados de teste são sintéticos. A documentação versionada contém somente método e síntese das medições.

## Interpretar

Ingestão principal: store JDBC real, lote 100, dois produtores e duas conexões. Comparação adicional: 10 mil eventos para cada lote 1/20/100, um produtor. Use `rowsPerSecond` por fase e considere concorrência/overhead do harness; `p95Millis` representa uma transação/lote. Não derive QPS HTTP desses valores.

Consultas: leitura por adaptador com cinco aquecimentos e 30 amostras, com JSON e transação read-only. Os planos preservam valores/parâmetros reais do adaptador. Páginas 1/20/100 verificam N+1 no servidor: uma chamada SELECT por página; comandos de transação não são consultas de dados.

Paginação profunda compara os mesmos IDs, por cursor e OFFSET, com projeção/mapper SQL equivalentes. Um anchor de teste é obtido previamente para garantir equivalência; esse custo não faz parte da continuação por cursor. A API pública continua sem OFFSET. Resultado de EXPLAIN não é garantia de plano genérico cacheado em todas as execuções.

IDs sequenciais, payload pequeno, filtros correlacionados, cache aquecido e host compartilhado afetam os resultados. `ANALYZE` é executado após a massa; os índices/triggers/fsync existentes permanecem ativos. Não há medição de payload de 64 KiB, carga HTTP/RabbitMQ, tráfego misto ou disponibilidade de cluster. A [ADR-033](../adr/033-medicao-auditoria-em-escala.md) detalha o método e os limites; consulte os [resultados desta entrega](../evidencias/etapa-08d2.md).

A vazão do store JDBC não equivale à do worker RabbitMQ: o worker executa um lote por tick. Com lote 20 e intervalo de 1 s, há no máximo cerca de 20 mensagens/s por instância antes dos custos de processamento. A cadeia completa, backlog e escolhas de lote/intervalo precisam ser medidas na etapa 10; o benchmark JDBC não altera esses defaults.

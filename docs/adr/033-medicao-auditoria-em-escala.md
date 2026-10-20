# ADR-033 — Medição reproduzível da auditoria em escala

- Data: 20/10/2026.
- Estado: aceita; implementação local da 8D2.
- Base: [ADR-028](028-base-auditoria-particionada.md), [ADR-031](031-consulta-auditoria-por-cursor.md) e [ADR-032](032-arquivamento-mensal-auditoria.md).

## Decisão

Adicionar `AuditPerformanceBenchmark` no código de testes do `iam-core`, executável explicitamente por `-Dit.test=AuditPerformanceBenchmark`. O nome fica fora dos padrões normais Surefire/Failsafe. O build/CI normal continua verificando regressões funcionais; o benchmark optativo produz medições e verifica correção, sem gates frágeis de latência vinculados à máquina.

`Measure-Audit.ps1` executa Maven com heap máximo de 1 GiB, benchmark selecionado e JaCoCo desativado nessa execução. O build completo verifica cobertura separadamente. A execução cria PostgreSQL 16.15 descartável, limitado a quatro CPUs e 4 GiB de RAM, aplica as migrações reais, provisiona o login de ingestão com o SQL administrativo e utiliza o pool restrito da aplicação. Não lê `.env`, não utiliza a stack persistente e remove a credencial temporária ao terminar.

## Massa e ingestão

Por padrão, gravar **2.000.000 de eventos** pelo `JdbcAuditEventStore.appendBatch`, em lotes de 100 e dois produtores concorrentes, usando as duas conexões do pool real. Toda a massa passa por JSONB, índices, triggers e recibos globais; não usa COPY, tabela unlogged ou desativação de constraints. Depois, medir mais 10.000 eventos em cada lote 1/20/100 com um produtor, totalizando 2.030.000 eventos/recibos na execução padrão.

Gerar eventos sintéticos pequenos, IDs sequenciais determinísticos, quatro tipos, mil recursos, dez mil atores, correlações únicas e timestamps distribuídos em 28 dias de três meses UTC. Os filtros de ator/recurso são correlacionados pelo gerador; a massa não representa distribuição real de produção ou payload máximo. O relógio do banco define o mês de referência.

O cronômetro inclui geração/serialização/JDBC/commit por lote; a vazão usa tempo de parede da fase, inclusive overhead do harness. Salvar amostras por lote e percentis nearest-rank p50/p95/p99. Distinguir latência de lote, eventos/s e número de produtores; comparar fases com concorrências diferentes exige essa distinção. Validar todos os resultados como APPENDED e as contagens finais de log/recibos.

## Consultas, N+1 e planos

Após ANALYZE, executar o adaptador real de consulta com janela mensal, limite 100 e seis cenários: tempo, tipo, recurso, ator, correlação e filtros combinados. Aquecer com cinco chamadas e medir 30 por cenário. A latência inclui transação read-only, busca e decodificação JSON; não inclui HTTP, autenticação ou RabbitMQ.

Para verificar N+1, limpar `pg_stat_statements` somente no contêiner descartável e medir SELECTs do login restrito nas páginas de 1, 20 e 100 itens, usando o serviço real. Exigir uma chamada SELECT independente do tamanho. O tracking é top-level: comandos de controle transacional não são classificados como buscas de dados; não se afirma contagem de instruções internas dos triggers durante ingestão.

Capturar o SQL e os parâmetros usados pelo adaptador e gerar `EXPLAIN (ANALYZE, BUFFERS, SETTINGS, FORMAT JSON)` sob o login restrito. Não forçar índices nem desabilitar sequential scan. Salvar planos de todas as variantes e comparar OFFSET profundo com corte por tupla equivalente, verificando os mesmos IDs. Essa comparação mede SQL/transferência com o mesmo mapper de IDs nas duas variantes; não é a latência do endpoint completo. O custo de obtenção inicial do anchor não entra na continuação por cursor.

As latências usam o comportamento padrão de preparação/cache do JDBC. EXPLAIN parametrizado mostra um plano com os valores daquela chamada; não prova que todo plano genérico eventualmente cacheado pelo driver é idêntico. Cache é aquecido, sem limpeza controlada do cache do sistema operacional. Repetições e percentis pequenos são amostras locais, sem intervalo de confiança ou certificação de SLA.

## Artefatos e limites

Gerar `iam-core/target/audit-benchmark/summary.json`, `samples.csv` e `plans/*.json`, com ambiente/configuração, tempos, vazão, SELECTs, planos e tamanhos das relações. Preservar esses arquivos antes de `clean`, pois targets são ignorados pelo Git. Documentação versionada resume os resultados realmente obtidos e o método; não versionar massa ou credenciais.

`pg_stat_statements` e I/O timing acrescentam instrumentação; fsync/synchronous_commit permanecem nas configurações registradas. O contêiner divide host/Docker/armazenamento com outros processos. A execução padrão não mede requisições/s OAuth, p95 do token endpoint, propagação de revogação sob carga, alta disponibilidade, ingestão via barramento ou tráfego misto concorrente. Esses cenários pertencem à etapa 10. Resultados não autorizam extrapolar vazão máxima ou alterar limites operacionais sem nova medição.

Operação: [runbook](../runbooks/medicao-auditoria-local.md). Resultados: [evidências](../evidencias/etapa-08d2.md).

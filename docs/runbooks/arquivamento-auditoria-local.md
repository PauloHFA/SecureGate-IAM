# Retenção quente e arquivo de auditoria — 8D1

## Preparação

Esta operação mantém dados no mesmo PostgreSQL, fora da consulta principal. Não é exclusão ou backup externo. Use somente com autorização administrativa sobre o mês escolhido; esta entrega não executa arquivamento contra a stack persistente automaticamente.

1. Build `./mvnw.cmd verify` e startup sem modos opcionais para aplicar Flyway **V16**. Encerre esse startup antes de reconfigurar os logins.
2. Reaplique `.\infra\scripts\Provision-AuditDatabase.ps1`: a ingestão precisa da nova permissão de execução de `is_archived_event_replay(jsonb)`. A senha existente é preservada. V16 não concede permissões globais a roles.
3. Reinicie os modos usados (`-EnableAuditConsumer`, consulta e manutenção) conforme os [guias 8B](consumidor-auditoria-local.md) e [8C](consulta-auditoria-local.md).
4. Escolha mês e política de retenção quente. Verifique backlog, eventos em trânsito, consultas em andamento e uma janela operacional para DDL bloqueante. Arquivos/recibos continuarão ocupando espaço no mesmo banco.

## Planejar e executar um mês

Na raiz, substitua o mês pelo período pretendido. O script sem `-Apply` executa apenas consulta de plano:

```powershell
.\infra\scripts\Archive-AuditMonth.ps1 -Month '2025-01' -HotMonths 6

# Após revisar o mês e a política, executar explicitamente:
.\infra\scripts\Archive-AuditMonth.ps1 -Month '2025-01' -HotMonths 6 -Apply
```

`-HotMonths` aceita 2–120, padrão 6, incluindo o mês UTC atual. Com mês atual outubro e seis meses, maio–outubro ficam anexados; abril ou anterior pode ser arquivado. O plano mostra elegibilidade, existência da relação de origem e catálogo; a função de aplicação ainda valida bounds/filiação antes de mover qualquer relação. Não aplica em lote nem aceita nomes de tabela.

O script lê a credencial administrativa de `.env`, passa a senha por ambiente, restaura as variáveis ao encerrar e não imprime valores de credenciais. Usa psql em `docker compose exec`, sem gravar senha em SQL/argumentos. Não altera `.env`. Repetir com os mesmos parâmetros de um mês já arquivado retorna a contagem registrada.

## Resultado e conferência administrativa

O mês sai de `iam_audit.event_log` e passa a existir como `iam_audit_archive.event_log_YYYYMM`. O catálogo e as linhas ficam imutáveis pelas operações DML protegidas; IDs, payloads, received_at e recibos são preservados. Não rode DROP, DELETE, TRUNCATE, desabilitação de triggers ou recriação manual do mês para contornar um erro.

Somente em conexão administrativa autorizada, sem expor a senha:

```sql
SELECT month, table_name, row_count, hot_months, archived_at
FROM iam_audit.archived_months ORDER BY month;

-- Substitua pelo mês efetivamente arquivado:
SELECT count(*) FROM iam_audit_archive.event_log_202501;
```

O log de arquivo pode conter metadados pessoais; restrinja acesso e não publique payloads em tickets. A API 8C consulta somente o parent quente: intervalo arquivado pode retornar vazio. Para analisar o arquivo, use o procedimento administrativo de leitura; não há endpoint unificado ou restore automático.

## Falhas e replay

- Mês atual/anterior, política inválida, dia diferente do primeiro ou candidato inesperado: operação recusada. Corrija a entrada, sem alterar tabelas para forçar aceite.
- Lock timeout (2 s) ou statement timeout (120 s): transação reverte; confira catálogo/filiação e repita em janela adequada. O parent usa ACCESS EXCLUSIVE durante contagem/detach; consumidores/consultas podem receber retry/503 temporário. A manutenção compartilha o advisory lock.
- Replay idêntico já registrado em mês arquivado: DUPLICATE, ACK após transação e nenhuma nova linha/recibo.
- Mesmo ID com envelope diferente: CONFLICT e DLQ. ID novo para mês arquivado: **LATE** e DLQ, sem reservar recibo. Não troque o ID/data para contornar a deduplicação/política; investigue o produtor e planeje eventual backfill administrativo separado.
- Mês sem partição que não consta no catálogo: segue RETRY, exigindo reparo de partição como na 8B.
- Após upgrade, falha de permissão no verificador: reaplique o provisionamento e confira o login; não conceda SELECT direto nos recibos/arquivos ou privilégios de proprietário à ingestão.

A retenção não reduz disco e não substitui estratégia de backup. Recibos são permanentes; política diferente não reabre meses arquivados. A próxima **8D2** mede planos SQL, N+1 e ingestão/consulta em escala. Consulte a [ADR-032](../adr/032-arquivamento-mensal-auditoria.md) para garantias e limites.

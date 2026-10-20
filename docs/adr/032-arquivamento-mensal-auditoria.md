# ADR-032 — Retenção quente e arquivamento mensal da auditoria

- Data: 19/10/2026.
- Estado: aceita; implementação local da 8D1.
- Base: [ADR-028](028-base-auditoria-particionada.md), [ADR-029](029-identidades-auditoria.md), [ADR-030](030-consumidor-auditoria-em-lote.md) e [ADR-031](031-consulta-auditoria-por-cursor.md).

## Escopo e política

Dividir a 8D em **8D1**, retenção quente/arquivo no PostgreSQL, e **8D2**, planos SQL, N+1 e medições com milhões de registros. Arquivar não significa apagar ou liberar espaço: os registros permanecem no mesmo banco, fora do parent consultado pela API. Não é backup externo, storage WORM, assinatura criptográfica do arquivo ou proteção contra administrador que altere DDL.

O operador escolhe explicitamente um mês UTC e a quantidade de meses quentes, entre 2 e 120, padrão 6. A janela inclui o mês atual: com seis meses e mês atual outubro, maio–outubro ficam quentes e abril ou anterior pode ser arquivado. A referência é o relógio UTC do banco. O mínimo de dois preserva mês atual/anterior, usados pela manutenção limitada da V14. Não há agendamento nem arquivamento durante Flyway/startup.

## V16 e operação atômica

A V16 cria `iam_audit_archive`, sem USAGE público, e o catálogo append-only `iam_audit.archived_months` com mês, nome derivado da data, número de linhas, meses quentes aplicados e instante do arquivo. `archive_event_month(date, hot_months)` é SECURITY INVOKER, sem EXECUTE para PUBLIC ou identidades runtime. É operação administrativa; não aceita nome de relação/SQL e não concede novas capacidades aos logins de aplicação.

Após validar elegibilidade, a função usa o mesmo advisory lock da manutenção, bloqueia o parent em ACCESS EXCLUSIVE, valida bounds/nome/filiação, conta linhas, desanexa a partição e move a tabela para o schema de arquivo. Adiciona triggers independentes contra INSERT/UPDATE/DELETE/TRUNCATE, mesmo quando o detach remove triggers clonados. Insere o catálogo na mesma transação. Erro ou rollback restaura tudo; repetição com os mesmos parâmetros retorna a contagem registrada. Não há DROP ou DELETE de dados/recibos.

O bloqueio interrompe temporariamente leituras/ingestão do parent e a contagem pode custar tempo em partições grandes. O script administrativo usa lock timeout de 2 s e statement timeout de 120 s; falha exige repetir após corrigir a causa. Não há promessa de zero downtime nem retenção online concorrente sem contenção. Arquivamento deve ocorrer em janela operacional adequada.

`ensure_event_partitions` recusa meses catalogados como arquivados. Assim histórico/manutenção não recria silenciosamente uma partição vazia para um mês fechado. A política nova não reabre arquivo existente; restauração/backfill deliberado exigem procedimento separado, não entregue como função runtime nesta etapa.

## Deduplicação e eventos tardios

Sem partição anexada, a inserção não chega ao trigger da V13. Para manter replay seguro, o store passa a usar `is_archived_event_replay(jsonb)` antes de produzir linhas para INSERT. É uma capacidade SECURITY DEFINER somente de leitura, com search_path/UTC fixos, sem acesso direto do login a recibos ou tabelas arquivadas. Reaplicar o provisionamento administrativo concede sua execução somente à ingestão.

- Mês quente: retorna false e segue o INSERT/trigger/transação existentes.
- Mês arquivado, recibo/instante/fingerprint idênticos: retorna true; zero linhas inseridas, classificado DUPLICATE e ACK após a transação.
- ID existente com conteúdo/instante conflitante: SQLSTATE 23505, isolamento e DLQ como CONFLICT.
- Novo ID em mês arquivado: SQLSTATE SG001, convertido em `ArchivedAuditMonthException`. O lote reverte; o worker isola por mensagem e rejeita só esse evento para DLQ com resultado **LATE**, permitindo progresso dos vizinhos.

Os recibos permanecem indefinidamente. Mudança de timestamp de um ID arquivado para mês quente continua sendo conflito no trigger global. Um mês ausente que não foi arquivado continua falha operacional recuperável por retry, como na 8B. Logs/erros não incluem envelopes, segredos ou SQL arbitrário.

## Consulta, permissões e limites

A API da 8C continua lendo somente `event_log`, portanto não retorna meses desanexados. Não há snapshot entre páginas; arquivar durante uma navegação pode retirar registros da continuação. Consulta de arquivos é administrativa no schema separado; não existe endpoint unificado de histórico frio.

Grants da 8A2 permanecem, acrescentando apenas EXECUTE do verificador de replay. Ingestão/manutenção não podem arquivar, ler arquivo/catalogar ou alterar seus dados. A conexão principal IAM ainda é proprietária/migradora; proprietário/superusuário pode mudar DDL. Os triggers não transformam o banco em armazenamento inviolável contra seu administrador.

Nenhum arquivo é exportado para disco/object storage. Não há expurgo, recuperação de espaço, restore automatizado, validação de backup externo, medição em milhões de linhas ou SLA sob carga. A contagem no catálogo é evidência operacional, não checksum de conteúdo. Essas limitações devem ser consideradas no planejamento de operação/backup.

Operação: [runbook](../runbooks/arquivamento-auditoria-local.md). Validação: [evidências](../evidencias/etapa-08d1.md).

# ADR-029 — Identidades e limites da auditoria

- Estado: aceita para implementação local na 8A2.
- Data: 2026-10-19.

## Identidades independentes

A V14 cria somente `maintain_event_partitions(months_ahead)`, SECURITY DEFINER, com search_path/UTC fixos e execução revogada de PUBLIC. A função aceita 1–12 meses futuros, calculados pelo relógio do banco, e inclui mês anterior/atual. Não recebe datas, nomes de tabelas ou SQL do chamador. `ensure_event_partitions` continua reservado ao proprietário para histórico/operação autorizada.

`infra/postgres/audit-privileges.sql` é provisionamento administrativo separado do Flyway, transacional e reaplicável. Cria dois grupos NOLOGIN e dois logins dedicados, sem SUPERUSER/CREATEDB/CREATEROLE/REPLICATION/BYPASSRLS. Recusa atributos, memberships ou ownership inesperados nos nomes reservados; remove concessões diretas antigas no schema de auditoria antes de conceder a matriz abaixo. Não remove dados ou muda ownership dos objetos.

| Identidade | Privilégios concedidos |
| --- | --- |
| `securegate_audit_ingest_login` → `securegate_audit_ingest` | USAGE do schema, INSERT/SELECT no parent `event_log` |
| `securegate_audit_maintenance_login` → `securegate_audit_maintenance` | USAGE do schema, EXECUTE somente no wrapper limitado da V14 |
| Proprietário/migração existente | Flyway, ownership dos objetos, backfill/DDL administrativo |

Ingestão não recebe escrita/leitura direta nos recibos, acesso à auditoria legada/Outbox, UPDATE/DELETE/TRUNCATE, DDL ou função de manutenção. O trigger SECURITY DEFINER da V13 registra os recibos globalmente sem conceder essa permissão ao login. Manutenção não lê/escreve eventos e não chama a função arbitrária de datas. Acesso via parent funciona em partições futuras; acesso direto aos filhos continua sem concessão.

## Pools e startup

`AuditDatabaseConnection` encapsula cada Hikari dedicado; não se registra como bean DataSource/JdbcTemplate global. Assim Boot preserva o DataSource principal e o transaction manager dos fluxos existentes. O store usa transações do pool de ingestão; o worker usa o pool de manutenção, após inicialização do banco. Os dois modos são opcionais e independentes.

Não há fallback para a credencial principal. Cada modo exige username dedicado e arquivo de senha hexadecimal de 64 caracteres. Configuração ausente/inválida impede startup do modo ativo. URL dedicada não aceita credenciais embutidas ou query string; as opções de conexão permitidas são definidas pelo pool. Senha é lida somente no startup; não há rotação/recarga automática.

Limites por pool: duas conexões, espera por conexão de 3 s, validação de 1 s, statement_timeout de 5 s, lock_timeout de 1 s, idle_in_transaction_session_timeout de 10 s e query timeout JDBC de 5 s. Driver usa connect/socket/cancel de 3/6/2 s. O provisionamento também define defaults de timeout nos logins, validados por autenticação real. São limites por operação/conexão, não orçamento total de um futuro lote. GUCs de usuário não constituem um limite inviolável contra cliente que controle suas próprias sessões.

## Credenciais e operação

`Provision-AuditDatabase.ps1` usa a identidade administrativa existente, gera/reutiliza senhas em `infra/.local` e aplica o SQL via psql. Valores vão por ambiente; `\getenv` e `\gset` evitam imprimir o resultado de configuração. Erros da atribuição de senha são sanitizados. O script restaura o ambiente do processo e grava arquivos somente após sucesso. Não altera `.env` e não põe senhas em argumentos ou arquivos SQL.

Uma nova geração ocorre se o arquivo não existir; não há coordenação de rotação com pools já ativos. Backup/proteção desses arquivos e rotação exigem procedimento operacional. Credenciais temporárias e custódia Vault ficam na etapa 9. O provisionamento requer administrador e V14 aplicada; startup com flags de auditoria só ocorre depois.

## Limites e próxima entrega

Esta entrega separa os caminhos de ingestão/manutenção, mas o processo IAM ainda mantém sua conexão principal proprietária, usada pelos contextos anteriores e por Flyway. Não se declara isolamento completo do processo contra comprometimento da credencial principal, nem proteção contra administrador que mude grants/DDL. A matriz é validada no banco configurado; hardening de cluster, TLS e credenciais temporárias continuam posteriores.

`-EnableAuditIngestion` disponibiliza o store com credencial restrita; não inicia consumidor RabbitMQ. A 8B deve conectar consumo em lote, confirmar após commit e tratar conflitos/retry, usando a identidade de ingestão. Consulta, retenção e medição em massa permanecem nas 8C/8D.

Referências: [guia](../runbooks/identidades-auditoria-local.md), [evidências](../evidencias/etapa-08a2.md) e [plano](../PLANO-DE-IMPLEMENTACAO.md).

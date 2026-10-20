# Identidades de auditoria — 8A2

## Preparar a infraestrutura existente

```powershell
.\mvnw.cmd -B -ntp clean verify
.\infra\scripts\Start-IamCore.ps1
```

O primeiro startup sem flags de auditoria aplica V14 com a conexão principal, preservando dados. Após confirmar readiness, encerre esse processo com Ctrl+C. Não limpe banco ou volumes.

Depois, com PostgreSQL/Compose disponíveis, execute como operador autorizado:

```powershell
.\infra\scripts\Provision-AuditDatabase.ps1
```

Esse comando altera roles/grants no PostgreSQL configurado em `.env`, usando sua credencial administrativa existente. Exige V14; Flyway não cria roles globais. Cria/reutiliza `infra/.local/audit-ingest-password` e `audit-maintenance-password`, ambos ignorados pelo Git. Nenhuma senha é exibida, passada em argumento ou gravada no SQL. Reaplicar preserva os arquivos existentes; arquivo ausente gera nova senha para o login correspondente, portanto não o apague como procedimento de atualização.

O script recusa roles reservadas com atributos/memberships/ownership incompatíveis. Investigue essa condição antes de corrigir grants; não renomeie identidades privilegiadas existentes para forçar reaplicação. Em falha SQL, a transação é desfeita e arquivos novos não são gravados.

## Ativar os caminhos dedicados

```powershell
.\infra\scripts\Start-IamCore.ps1 -EnableAuditPartitions -EnableAuditIngestion
```

O primeiro switch agenda manutenção limitada; o segundo disponibiliza o store de ingestão. Não há consumidor de `audit.persist` nesta entrega. As opções podem ser ativadas separadamente e não implicam OAuth/publicador.

Ambas usam URL dedicada derivada da configuração local e usernames fixos. Em configuração direta, consulte `iam.audit.partitions.{database-url,username,password-file}` e `iam.audit.ingestion.{database-url,username,password-file}`. A URL não aceita userinfo/query string; credenciais são fornecidas somente pelos arquivos. Modo ativo com arquivo/login/configuração inválidos não usa a conexão principal como alternativa.

## Matriz de operação

| Caminho | Permitido | Recusado |
| --- | --- | --- |
| Ingestão | INSERT/SELECT no parent; recibo via trigger; replay idêntico | UPDATE/DELETE/TRUNCATE, recibo direto, auditoria legada, Outbox, DDL, manutenção |
| Manutenção | EXECUTE do wrapper atual/próximos 1–12 meses | SELECT/INSERT de auditoria, DDL direto, datas históricas, horizonte maior |
| Proprietário/migração | Flyway e DDL/backfill autorizado | Não é uma identidade restrita de ingestão |

Acesso via parent cobre novas partições sem grants extras nos filhos. Leitura/alteração direta das partições não é concedida aos logins dedicados. O wrapper usa o relógio UTC do banco; operação histórica permanece manual e autorizada via função proprietária da [8A1](auditoria-particionada-local.md).

## Limites, métricas e diagnóstico

Cada pool tem no máximo duas conexões; espera por conexão de 3 s; statement/query timeout de 5 s; lock timeout de 1 s e idle em transação de 10 s. Contenção deve terminar em erro recuperável, sem queda permanente do worker. As métricas de manutenção da 8A1 permanecem e o scrape continua sem SQL.

Em erro de autenticação, confira provisionamento, username e arquivo do modo correspondente. Em permission denied, confira grants e V14 antes de ampliar qualquer permissão; não use a credencial principal para contornar a restrição. Em lock timeout, investigue transações/DDL concorrentes; o próximo tick pode tentar novamente. Não desabilite triggers ou apague recibos.

Os pools carregam senhas no startup. Alterar login/arquivo exige coordenar parada, provisionamento e reinício; rotação automática não foi implementada. Os testes usam credenciais geradas e PostgreSQL descartável; não executam o script contra a stack persistente.

A conexão principal do processo IAM continua proprietária/migradora. A proteção aqui é dos logins dedicados, não de um processo que tenha sido comprometido junto com a credencial principal. A 8B integra o consumidor; custódia Vault e credenciais temporárias ficam na etapa 9. Veja [ADR-029](../adr/029-identidades-auditoria.md) e [evidências](../evidencias/etapa-08a2.md).

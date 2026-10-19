# Operação da Outbox — 6C

## Iniciar e consultar métricas

Com infraestrutura local saudável e JAR atualizado:

```powershell
.\infra\scripts\Start-IamCore.ps1 -EnableBootstrap -EnableOutboxPublisher
```

Flyway aplica a V12. O worker operacional coleta métricas a cada 30 segundos, independentemente do publicador. A limpeza permanece desabilitada. Em outra janela, leia o token local sem imprimi-lo:

```powershell
. .\infra\scripts\LocalEnvironment.ps1
$settings = Read-LocalEnvironment (Join-Path (Get-Location) '.env')
$headers = @{ Authorization = 'Bearer ' + $settings.IAM_BOOTSTRAP_TOKEN }
$metricsBase = 'http://127.0.0.1:8080/actuator/metrics'
Invoke-RestMethod -Uri "$metricsBase/iam.outbox.pending" -Headers $headers
Invoke-RestMethod -Uri "$metricsBase/iam.outbox.snapshot.available" -Headers $headers
Invoke-RestMethod -Uri "$metricsBase/iam.outbox.snapshot.timestamp" -Headers $headers
```

Sem bootstrap, métricas retornam 403; com bootstrap e token ausente/inválido, 401. Token válido permite leitura GET. Sessão de usuário e JWT OAuth não concedem esse acesso. O endpoint lista também outras métricas do processo; não exponha o mecanismo bootstrap fora do ambiente local previsto. Não há exportador Prometheus nesta entrega.

| Métrica (`iam.outbox.`) | Significado |
| --- | --- |
| `pending` | Todos os eventos não publicados, incluindo quarentena |
| `due` | Elegíveis agora, sem quarentena ou lease ativo |
| `leased` / `expired` | Pendências com lease ativo / vencido |
| `quarantined` | Pendências suspensas |
| `failed` | Pendências com última falha registrada, podendo incluir lease/quarentena |
| `published` | Publicados ainda retidos na Outbox |
| `oldest_seconds` | Idade da pendência mais antiga na última coleta, incluindo quarentena |
| `snapshot.available` | 1 após coleta bem-sucedida; 0 antes da primeira coleta ou após falha |
| `snapshot.timestamp` | Unix epoch em segundos da última coleta bem-sucedida; 0 antes dela |
| `delivery`, tag `result` | Marcação confirmada (`published`), lease perdido (`lease_lost`) ou código da falha persistida |
| `store.errors`, tag `operation` | Erros em claim, confirmação ou retry no banco |
| `purged` | Linhas publicadas removidas por esta instância |
| `maintenance.errors`, tag `operation` | Falhas de `snapshot` ou `retention` |

Contadores são locais ao processo e reiniciam com ele; não equivalem ao histórico durável. As categorias de gauges se sobrepõem. Antes da primeira coleta, gauges de backlog são NaN. Após falha, conservam valores antigos: verifique disponibilidade e timestamp antes de interpretá-los. Como ponto inicial local, investigue coleta com mais de dois intervalos de atraso, quarentena maior que zero, crescimento sustentado do backlog e leases vencidos. Não há SLO de propagação comprovado até implementar/medir os consumidores.

## Retenção opcional

Para remover somente publicações confirmadas há mais de sete dias, em lotes de até 100 por ciclo:

```powershell
.\infra\scripts\Start-IamCore.ps1 -EnableBootstrap -EnableOutboxPublisher -EnableOutboxRetention
```

O switch controla a retenção somente no processo iniciado e restaura o ambiente ao sair. Pode ser usado sem o publicador. Não exige novas credenciais nem remoção de volumes. Para desativar, reinicie sem `-EnableOutboxRetention`.

| Propriedade `iam.outbox.operations.` | Variável de ambiente | Padrão / limite |
| --- | --- | --- |
| `retention-enabled` | `IAM_OUTBOX_RETENTION_ENABLED` | false |
| `retention-days` | `IAM_OUTBOX_RETENTION_DAYS` | 7 / 1–3650 dias |
| `batch-size` | `IAM_OUTBOX_RETENTION_BATCH_SIZE` | 100 / 1–1000 linhas |
| `poll-millis` | `IAM_OUTBOX_OPERATIONS_POLL_MILLIS` | 30000 / 1000–3600000 ms |

Mude os três limites pelo ambiente antes de iniciar. O script controla `retention-enabled` pelo switch. A limpeza conta desde `published_at`, preserva auditoria e histórico operacional e nunca apaga pendências/quarentenas. Falha no banco desfaz o lote; o próximo ciclo tenta novamente. ACK do broker não comprova consumo. Após expurgar a Outbox, replay exige outra fonte/política; não use a limpeza como solução para filas RabbitMQ cheias.

## Investigar e colocar em quarentena

Na conexão PostgreSQL local autenticada como operador autorizado:

```sql
SELECT event_id,event_type,created_at,attempts,last_error_code,
       next_attempt_at,lease_until,quarantined_at,published_at
FROM iam_audit.outbox_events
WHERE published_at IS NULL
ORDER BY created_at,event_id LIMIT 50;
```

Não inclua payloads ou credenciais em tickets/logs. `INVALID_EVENT` entra automaticamente em quarentena. Falha permanente de rota/configuração pode ser suspensa após diagnóstico. Copie o UUID real da consulta e execute, com uma conta de banco confiável:

```sql
BEGIN;
SELECT iam_audit.set_outbox_quarantine('00000000-0000-0000-0000-000000000000'::uuid,true);
COMMIT;
```

Substitua o UUID de exemplo. Retorno true indica uma transição; false indica evento ausente, publicado ou já no estado solicitado. A suspensão invalida o lease, mas uma mensagem já em trânsito ainda pode chegar ao broker. A função não é SECURITY DEFINER e não concede privilégios a PUBLIC. Na stack local, o proprietário do schema pode executá-la; em produção, use uma conta operacional separada e privilégios mínimos.

## Recuperar e liberar

1. Para `BROKER_UNAVAILABLE`, verifique broker, vhost, credenciais, rede e permissões. Recupere o serviço e aguarde o backoff, limitado a cinco minutos.
2. Para `UNROUTABLE`, restaure a topologia compartilhada e os bindings previstos. O ACK com outra rota não comprova entrega a todas as filas desejadas.
3. Para `NACK`/`CONFIRM_TIMEOUT`, verifique recursos do broker e conectividade. O retry pode duplicar a mensagem; preserve eventId.
4. Para `INVALID_EVENT`, verifique contrato/compatibilidade do leitor. Corrija o código e valide antes de liberar. Não edite o payload imutável nem fabrique confirmação de publicação. Sem correção compatível, preserve o registro em quarentena e trate o incidente explicitamente.
5. Para lease vencido, confirme a saúde dos workers e do banco. A recuperação é automática; não apague a pendência nem altere `published_at` manualmente.

Depois de corrigir a causa de um evento em quarentena:

```sql
BEGIN;
SELECT iam_audit.set_outbox_quarantine('00000000-0000-0000-0000-000000000000'::uuid,false);
COMMIT;

SELECT event_id,occurred_at,database_actor,action,reason
FROM iam_audit.outbox_operations
ORDER BY id DESC LIMIT 30;
```

A liberação mantém eventId/payload, limpa a última falha e agenda nova tentativa imediata. Se o leitor continuar recusando, volta à quarentena. Transições são registradas atomicamente e seu histórico não é removido pela retenção. `database_actor` identifica a sessão de banco, não uma pessoa quando a conta é compartilhada. UPDATE/DELETE no histórico e UPDATE do payload são recusados por triggers; isso não impede um proprietário/superusuário de alterar DDL.

Queda do banco mantém o último retrato com `snapshot.available=0`; não interprete isso como backlog zero. Resolva a conectividade, acompanhe a próxima coleta e confira os contadores de falha. A readiness continua dependente do banco; indisponibilidade isolada do broker é tratada pelo backlog. Filas de consumidor e DLQs exigem investigação própria; a etapa 7 ainda implementará seus consumidores.

Validação: [evidências 6C](../evidencias/etapa-06c.md). Decisões: [ADR-021](../adr/021-operacao-da-outbox.md). Publicação: [guia 6B](publicacao-outbox-local.md).

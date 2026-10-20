# Consulta de auditoria local — 8C

## Ativação

Após `./mvnw.cmd verify`, prepare a infraestrutura e os logins conforme [8A2](identidades-auditoria-local.md). O startup aplica Flyway V15 e seus índices; a criação pode bloquear escritas em bases com dados. Não use este runbook como procedimento de migração online em produção.

```powershell
# Consulta o log existente; não inicia consumidor/publicador.
.\infra\scripts\Start-IamCore.ps1 -EnableBootstrap -EnableAuditQuery

# Para publicar, consumir e consultar novos eventos localmente:
.\infra\scripts\Start-IamCore.ps1 -EnableBootstrap -EnableOutboxPublisher -EnableAuditConsumer -EnableAuditPartitions -EnableAuditQuery
```

`-EnableAuditQuery` implica `-EnableAuditIngestion` para fornecer o pool restrito, mas não inicia consumidor nem habilita bootstrap automaticamente. Sem bootstrap não existe acesso administrativo autorizado nesta configuração. Senhas continuam em arquivos ignorados de `infra/.local`; `.env` não é alterado. Consulte [8B](consumidor-auditoria-local.md) para geração, consumo e recuperação dos eventos.

## Requisição e continuação

Em outra sessão PowerShell, reutilize o leitor local de `.env` sem imprimir o token:

```powershell
. .\infra\scripts\LocalEnvironment.ps1
$localSettings = Read-LocalEnvironment (Join-Path $PWD '.env')
$headers = @{ Authorization = 'Bearer ' + $localSettings.IAM_BOOTSTRAP_TOKEN }
$auditTo = [DateTime]::UtcNow
$auditFrom = $auditTo.AddHours(-24)
# Formato com seis casas: PostgreSQL trabalha com microssegundos.
$auditParameters = @{
  from = $auditFrom.ToString("yyyy-MM-ddTHH:mm:ss.ffffff'Z'")
  to = $auditTo.ToString("yyyy-MM-ddTHH:mm:ss.ffffff'Z'")
  limit = '20'
}
function Get-AuditPage($parameters, $headers) {
  $pairs = foreach ($key in $parameters.Keys) {
    [Uri]::EscapeDataString([string]$key) + '=' + [Uri]::EscapeDataString([string]$parameters[$key])
  }
  Invoke-RestMethod -Uri ('http://127.0.0.1:8080/api/admin/audit/events?' + ($pairs -join '&')) -Headers $headers
}
$auditPage = Get-AuditPage $auditParameters $headers
$auditPage.items | Select-Object id, occurredAt, receivedAt
if ($auditPage.nextCursor) {
  $auditParameters.cursor = $auditPage.nextCursor
  $auditPage = Get-AuditPage $auditParameters $headers
}
```

Fixe a janela e os filtros para todas as páginas: recalcular `to` invalida o cursor. Adicione `eventType`, `resource`, `actor` ou `correlationId` antes da primeira chamada para filtrar por igualdade; remova `cursor` ao mudar filtros. Valores são sensíveis a maiúsculas e espaços. `limit` pode variar de 1 a 100; ausência de `nextCursor` (null) indica fim da continuação observada.

Cada item contém `id`, `occurredAt` e `receivedAt` do banco, além de `event`, o envelope original. `event.occurredAt` pode ter nanossegundos; a navegação usa o `occurredAt` externo, com precisão do banco. Não construa cursores a partir do payload. Não publique corpos de auditoria ou tokens em logs/tickets.

## Contrato e diagnóstico

| Campo | Regra |
| --- | --- |
| `from`, `to` | Obrigatórios; ISO-8601 com offset, normalizado em UTC, precisão de até seis casas, intervalo `[from,to)` de até 31 dias |
| `eventType` | Tipo de evento válido, igualdade exata |
| `resource`, `actor`, `correlationId` | Opcionais; até 100, 200 e 64 caracteres, respectivamente; sem controles/valor vazio |
| `limit` | Padrão 20, 1–100 |
| `cursor` | Retornado na página anterior; mesmos filtros/janela |

- 401: bootstrap habilitado, credencial administrativa ausente/incorreta. Com bootstrap desabilitado, acesso administrativo recebe 403. Um JWT OAuth de usuário não substitui o token administrativo nesta rota.
- 404 após autenticar: consulta opcional desabilitada. `IAM_AUDIT_QUERY_ENABLED=true` também requer `IAM_AUDIT_INGESTION_ENABLED=true` e suas configurações dedicadas; o script prepara ambas.
- 400: intervalo/precisão/filtro/limite/cursor inválido, parâmetro desconhecido/repetido ou mudança de filtros na continuação.
- 503: pool/banco/lock/statement indisponível. Verifique infraestrutura, permissões e concorrência. Depois de corrigir, repita a mesma página/cursor. Não há fallback para a conexão proprietária.
- Resultado vazio pode significar que os eventos ainda não foram ingeridos, estão fora da janela, não correspondem aos filtros ou pertencem somente à auditoria legada. Verifique a fila/Outbox conforme 8B; esta API só lê `iam_audit.event_log`.

O cursor evita o custo de OFFSET e resolve empates. Não mantém snapshot: inserções posteriores acima do cursor não entram na continuação; chegadas tardias abaixo dele podem entrar. Não há contagem total nem busca adicional por item. Os índices disponíveis são registrados na [ADR-031](../adr/031-consulta-auditoria-por-cursor.md); desempenho sob carga e retenção pertencem à 8D.

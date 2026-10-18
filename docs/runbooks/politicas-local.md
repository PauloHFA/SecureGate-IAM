# Políticas locais — entrega 5A

Esta entrega permite administrar políticas e simular decisões. A simulação não concede acesso, não altera tokens e não comprova MFA. A integração ao login/emissão/recursos será feita na 5D.

## Preparar

Com JDK 21 e Docker, executar o build e preparar a infraestrutura conforme o [guia local](../../infra/README.md). Na raiz do projeto:

```powershell
.\mvnw.cmd -B -ntp clean verify
.\infra\scripts\Start-IamCore.ps1 -EnableBootstrap
```

Não é necessário habilitar Authorization/Transit para administrar políticas. Na inicialização, Flyway aplica V5 ao banco local. Se precisar de OAuth simultaneamente, use os parâmetros documentados no [guia OIDC](oidc-local.md).

Em outro PowerShell:

```powershell
. .\infra\scripts\LocalEnvironment.ps1
$settings = Read-LocalEnvironment (Join-Path (Get-Location) '.env')
$baseUrl = 'http://127.0.0.1:8080'
$adminHeaders = @{ Authorization = 'Bearer ' + $settings.IAM_BOOTSTRAP_TOKEN }
$userId = Read-Host 'UUID de um usuario ACTIVE provisionado'
```

Use o [guia de provisionamento](provisionamento-local.md) para cadastrar usuário e associar um papel `READER`, diretamente ou por grupo. Não há papel implícito por nome nem política padrão de permissão. Conta inexistente, bloqueada ou desativada resulta em DENY na simulação.

## Criar uma política

```powershell
$policyBody = @{
  name = 'invoice-reader'
  priority = 100
  enabled = $true
  document = @{
    schemaVersion = 1
    resource = 'invoices'
    action = 'read'
    effect = 'ALLOW'
    rolesAnyOf = @('READER')
    allowedScopes = @()
    maxAccessTokenTtlSeconds = $null
  }
}
$policy = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/admin/policies" `
  -Headers $adminHeaders -ContentType 'application/json' `
  -Body ($policyBody | ConvertTo-Json -Depth 8)
$policy | Select-Object id, name, priority, enabled, version
```

O documento é um objeto JSON na API e JSONB no banco. Criação retorna 201 e versão 0. Nomes são únicos, de 3–64 caracteres minúsculos, dígitos, `_` ou `-`, começando com letra. Prioridade vai de 0 a 1000. `resource`/`action` usam até 128 caracteres `[a-z][a-z0-9:_./-]*`, ou `*` para o campo inteiro. A avaliação exige valores concretos e não aceita `*` como pedido.

`rolesAnyOf` exige pelo menos um dos até 20 papéis informados; lista vazia aplica a qualquer usuário ACTIVE. Campos desconhecidos e expressões são rejeitados, assim como versão de esquema inválida. Use números JSON para schemaVersion/TTL. A gramática ABAC e o score de risco ainda não estão disponíveis.

## Simular

```powershell
$evaluation = @{
  userId = $userId
  resource = 'invoices'
  action = 'read'
  requestedScopes = @('api:read', 'api:write')
} | ConvertTo-Json
$decision = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/admin/policies/evaluate" `
  -Headers $adminHeaders -ContentType 'application/json' -Body $evaluation
$decision | Select-Object effect, reasons, allowedScopes, maxAccessTokenTtlSeconds, mfaRequired
$decision.appliedPolicies
```

Com READER, a regra retorna ALLOW, com os escopos solicitados e teto de 300 segundos. Sem papel correspondente ou sem política habilitada, retorna DENY/NO_MATCH. Nenhum TTL de token é alterado por essa chamada. Os papéis vêm do banco, incluindo grupos; mudanças de atribuição são observadas na próxima simulação. O endpoint não recebe papéis confiáveis, score ou fator MFA do cliente.

## Combinar limites e MFA

```powershell
$limitedBody = @{
  name = 'invoice-reader-limit'
  priority = 50
  enabled = $true
  document = @{
    schemaVersion = 1
    resource = 'invoices'
    action = 'read'
    effect = 'ALLOW_LIMITED'
    rolesAnyOf = @('READER')
    allowedScopes = @('api:read')
    maxAccessTokenTtlSeconds = 90
  }
}
$limited = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/admin/policies" `
  -Headers $adminHeaders -ContentType 'application/json' `
  -Body ($limitedBody | ConvertTo-Json -Depth 8)
```

Agora a simulação retorna ALLOW_LIMITED, somente api:read e teto de 90 segundos. Limites precisam de pelo menos um escopo e TTL de 30–300 segundos. Todas as restrições são intersectadas: interseção vazia nega. Uma regra ALLOW adicional não remove limites.

Para MFA, crie outra regra correspondente com efeito `ALLOW_WITH_MFA`, escopos de restrição vazios e TTL nulo. A decisão será ALLOW_WITH_MFA com `mfaRequired: true`, preservando os limites de 90 segundos/api:read. O fator ainda não foi implementado: essa resposta significa que a exigência permanece pendente.

Qualquer DENY habilitado correspondente prevalece, mesmo com prioridade menor. A prioridade só ordena as políticas explicadas; não exclui proibições ou restrições. IDs/versões retornados permitem identificar quais regras participaram. Consulte a [tabela completa](../adr/010-politicas-e-tabela-de-decisao.md).

## Consultar e editar

```powershell
$current = Invoke-RestMethod "$baseUrl/api/admin/policies/$($policy.id)" -Headers $adminHeaders
$replacement = @{
  name = $current.name
  priority = $current.priority
  enabled = $false
  version = $current.version
  document = $current.document
} | ConvertTo-Json -Depth 8
$updated = Invoke-RestMethod -Method Put -Uri "$baseUrl/api/admin/policies/$($policy.id)" `
  -Headers $adminHeaders -ContentType 'application/json' -Body $replacement
$updated | Select-Object id, enabled, version

$page = Invoke-RestMethod "$baseUrl/api/admin/policies?limit=20&offset=0" -Headers $adminHeaders
$page | Select-Object id, name, priority, enabled, version
```

PUT substitui a configuração inteira. A versão sobe para 1; repetir a versão anterior retorna 409. Releia antes de reaplicar uma edição após conflito. Não existe DELETE: desabilite com PUT. Listagem aceita limit 1–100 e offset 0–10000, ordenada por prioridade decrescente e UUID.

| Resultado | Significado |
| --- | --- |
| 401 com bootstrap ativo | Credencial ausente/incorreta |
| 403 com bootstrap desabilitado | Administração desabilitada |
| 400 | Documento, versão de esquema, target, escopos ou parâmetros inválidos |
| 404 | Política inexistente |
| 409 | Nome duplicado ou versão concorrente |
| 500 | Falha de persistência/auditoria ou regra armazenada inválida; nenhuma autorização é concedida |
| DENY/POLICY_LIMIT_EXCEEDED | Mais de 1000 políticas habilitadas candidatas para o target; avaliação interrompida |

Criação/edição e auditoria são atômicas. Não registre cabeçalhos administrativos nem dados pessoais nos nomes/documentos. A 5A não publica eventos RabbitMQ, não aplica políticas ao OAuth e não verifica sinais de dispositivo/rede. Veja [ADR-010](../adr/010-politicas-e-tabela-de-decisao.md), [evidências](../evidencias/etapa-05a.md) e [roteiro](../PLANO-DE-IMPLEMENTACAO.md).

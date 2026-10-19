# ABAC e risco local — entrega 5B1

Prepare a aplicação com `Start-IamCore.ps1 -EnableBootstrap` e carregue `$baseUrl`, `$adminHeaders` e `$userId` conforme o [guia de políticas](politicas-local.md). Não é necessário habilitar OAuth/Transit. Flyway aplica V6, preservando políticas v1.

Este guia testa regras e cenários da 5B1 em ferramentas administrativas. A [5B2](coleta-risco-local.md) acrescenta coleta real ao caminho normal. Cenários sintéticos continuam separados, não comprovam contexto e nunca satisfazem MFA. As decisões ainda não mudam grants/tokens.

## Regra v2 de baixo risco

```powershell
$rule = @{
  name = 'invoice-low-risk'
  priority = 100
  enabled = $true
  document = @{
    schemaVersion = 2
    resource = 'invoices'
    action = 'read'
    effect = 'ALLOW'
    rolesAnyOf = @('READER')
    allowedScopes = @()
    maxAccessTokenTtlSeconds = $null
    conditions = @(
      @{ attribute = 'DEVICE_TRUST'; operator = 'EQ'; values = @('TRUSTED') }
      @{ attribute = 'RISK_SCORE'; operator = 'LTE'; values = @('29') }
    )
  }
}
$created = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/admin/policies" `
  -Headers $adminHeaders -ContentType 'application/json' `
  -Body ($rule | ConvertTo-Json -Depth 12)
$created | Select-Object id, version
```

Use um usuário ACTIVE com READER atribuído diretamente ou por grupo. Se já houver regras para invoices/read, elas também participam: DENY prevalece independentemente da prioridade. Use nome/target próprio para isolar uma demonstração.

O campo `conditions` é obrigatório na v2, podendo ser vazio. Todas as condições precisam corresponder. Máximo de dez condições e dez valores por condição. Na v1, `conditions` não é permitido. Nomes de atributos/operadores são enums fechados; valores numéricos de condições são strings como `'29'`, sem conversão automática. Consulte a [gramática](../adr/011-abac-e-risco-deterministico.md).

## Avaliação normal

```powershell
$evaluation = @{
  userId = $userId
  resource = 'invoices'
  action = 'read'
  requestedScopes = @('api:read')
}
$actual = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/admin/policies/evaluate" `
  -Headers $adminHeaders -ContentType 'application/json' `
  -Body ($evaluation | ConvertTo-Json -Depth 6)
$actual | Select-Object effect, reasons, simulation
$actual.risk
```

O caminho normal informa `simulation: false`. Desde a 5B2, os sinais vêm das fontes descritas no [guia de coleta](coleta-risco-local.md). Este exemplo não fornece contextId, portanto dispositivo e rede permanecem UNKNOWN; outros sinais podem existir ou estar em aquecimento. Sem condição correspondente, retorna DENY/NO_MATCH; uma regra potencialmente aplicável que dependa de atributo desconhecido provoca DENY/INDETERMINATE_ATTRIBUTES. Um DENY comprovado prevalece com EXPLICIT_DENY. Enviar score/cenário/headers extras a evaluate não fornece evidência nem reduz risco.

Uma regra v1 sem condições de risco mantém o comportamento anterior; score 100, por si só, não transforma um ALLOW v1 em DENY. Crie políticas explícitas para usar score/faixa como condição.

## Simular contexto conhecido

```powershell
$simulation = @{
  userId = $userId
  resource = 'invoices'
  action = 'read'
  requestedScopes = @('api:read')
  scenario = @{
    deviceTrust = 'TRUSTED'
    networkTrust = 'TRUSTED'
    failedLoginAttempts = 0
    resourceSensitivity = 'PUBLIC'
  }
}
$predicted = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/admin/policies/simulate" `
  -Headers $adminHeaders -ContentType 'application/json' `
  -Body ($simulation | ConvertTo-Json -Depth 8)
$predicted | Select-Object effect, reasons, simulation, mfaRequired
$predicted.risk
```

Esse cenário retorna score 0/LOW e pode produzir ALLOW se a identidade/papel corresponderem e não houver regra conflitante. A resposta sempre tem `simulation: true`. Repetir a avaliação normal continua usando seus próprios sinais: o cenário não é persistido ou promovido a evidência.

Altere deviceTrust/networkTrust para UNTRUSTED, failedLoginAttempts para 5 e resourceSensitivity para RESTRICTED para obter score 100/CRITICAL. Os campos podem ser omitidos dentro de `scenario`, produzindo UNKNOWN e os pontos conservadores correspondentes. `scenario` inteiro é obrigatório; valores inválidos como contador negativo ou enum em minúsculas retornam 400. Não há campos de papéis, score pronto, fator MFA ou instante de observação controlados pelo cenário.

## Regras de MFA/negação por faixa

Crie outras políticas v2 com os mesmos target/papéis e estes efeitos/condições, usando nomes únicos:

| Efeito | Condições ALL |
| --- | --- |
| ALLOW_WITH_MFA | RISK_SCORE GTE `30` e LTE `79` |
| DENY | RISK_SCORE GTE `80` |
| ALLOW_LIMITED | RESOURCE_SENSITIVITY IN `CONFIDENTIAL`, `RESTRICTED` |

ALLOW_LIMITED continua exigindo allowedScopes não vazio e TTL de 30–300 segundos; os demais efeitos usam lista vazia/TTL nulo. Ao combinar MFA e limite, mfaRequired permanece true e a resposta mantém os escopos intersectados e o menor TTL. Isso indica requisito pendente, sem autenticar o fator.

O score é soma fixa: dispositivo 0/25, rede 0/20, falhas 0/10/20/30, recurso 0/5/15/25. Ausência usa o maior peso do sinal. Faixas LOW 0–29, MEDIUM 30–59, HIGH 60–79 e CRITICAL 80–100. Pesos são uma baseline sem calibração estatística. Evidência vencida ou futura além da tolerância é tratada como ausente pelo contrato documentado; não há endpoint de ingestão nesta entrega.

Para ver as regras utilizadas, consulte `appliedPolicies` (IDs e versões). Condições indeterminadas em uma regra potencialmente aplicável provocam DENY; elas não são simplesmente descartadas para permitir por outra regra. Um atributo comprovadamente incompatível torna a conjunção ALL falsa, mesmo se outro estiver ausente.

Atualização, desativação, concorrência, auditoria e paginação continuam conforme a [5A](politicas-local.md). A coleta está no [guia 5B2](coleta-risco-local.md); próximas entregas são 5C (MFA) e 5D (aplicação). Consulte [ADR-011](../adr/011-abac-e-risco-deterministico.md), [evidências históricas](../evidencias/etapa-05b1.md) e [roteiro](../PLANO-DE-IMPLEMENTACAO.md).

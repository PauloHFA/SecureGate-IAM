# Coleta de risco local — entrega 5B2

Prepare a infraestrutura e um usuário ACTIVE pelo [guia de provisionamento](provisionamento-local.md). Para registrar contexto real de navegador, inicie com:

```powershell
.\infra\scripts\Start-IamCore.ps1 -EnableAuthorization -EnableBootstrap
```

Authorization usa Vault/Transit e habilita login. Flyway aplica V7 na inicialização. Carregue `$baseUrl`, `$adminHeaders` e `$userId` conforme o [guia de políticas](politicas-local.md). Use o login pelo navegador ou o fluxo [PKCE](oauth-pkce-local.md). Não há API pública para enviar IP/dispositivo/contador como fatos.

## Obter contexto real

Após login, visite `/login` ou prossiga no consentimento. Em outro PowerShell com autorização administrativa:

```powershell
$contexts = Invoke-RestMethod "$baseUrl/api/admin/risk/users/$userId/contexts" -Headers $adminHeaders
$context = @($contexts)[0]
$context | Select-Object id, deviceId, observedAt, deviceVersion, trustedUntil
```

A lista inclui no máximo vinte contextos atuais desse usuário, ordenados por observação. Confirme que escolheu a sessão desejada. Não há seleção automática de “última sessão” na avaliação: informe contextId explicitamente. Não use contextos de outro usuário. A API omite IP/JSESSIONID e é no-store.

Novo navegador recebe cookie sg_device e começa sem aprovação. Cookie é HttpOnly/SameSite=Lax; em TLS também Secure. A aprovação representa somente navegador lembrado. Não satisfaz MFA nem atesta hardware.

## Classificar recurso e rede

```powershell
$resource = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/admin/risk/resources" `
  -Headers $adminHeaders -ContentType 'application/json' `
  -Body (@{ resource = 'invoices'; sensitivity = 'PUBLIC' } | ConvertTo-Json)

# Exemplo explícito para acesso direto de loopback no ambiente local.
$network = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/admin/risk/networks" `
  -Headers $adminHeaders -ContentType 'application/json' `
  -Body (@{ cidr = '127.0.0.0/8' } | ConvertTo-Json)
```

O recurso aceita PUBLIC/INTERNAL/CONFIDENTIAL/RESTRICTED; sem perfil, fica UNKNOWN. Cadastros duplicados retornam 409. A rede é uma allowlist administrada: sem correspondência habilitada, uma origem observada é UNTRUSTED. Faixas IPv4/IPv6 são normalizadas pelo banco; não são resolvidos hostnames.

A origem é a conexão ao IAM. Forwarded/X-Forwarded-For não são aceitos como prova; o launcher/configuração atual ignora forwarded headers. Atrás de um proxy, a origem observada é o proxy, exigindo definição futura do modelo de proxy confiável para classificar o usuário final.

## Aprovar navegador com prazo

```powershell
$deviceUrl = "$baseUrl/api/admin/risk/users/$userId/devices/$($context.deviceId)"
$device = Invoke-RestMethod $deviceUrl -Headers $adminHeaders
$approval = Invoke-RestMethod -Method Put -Uri $deviceUrl `
  -Headers $adminHeaders -ContentType 'application/json' `
  -Body (@{ trusted = $true; ttlSeconds = 600; version = $device.version } | ConvertTo-Json)
$approval | Select-Object deviceId, version, trustedUntil
```

Prazo de 60–86400 segundos; exemplo de dez minutos. Versão antiga retorna 409. Releia antes de reaplicar. Dispositivo inexistente ou pertencente a outro usuário retorna 404; aprovar usuário inativo é rejeitado. Expiração volta a UNTRUSTED, sem precisar editar o contexto.

## Avaliar sem simulação

```powershell
$evaluation = @{
  userId = $userId
  resource = 'invoices'
  action = 'read'
  requestedScopes = @('api:read')
  contextId = $context.id
}
$result = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/admin/policies/evaluate" `
  -Headers $adminHeaders -ContentType 'application/json' `
  -Body ($evaluation | ConvertTo-Json -Depth 6)
$result | Select-Object effect, reasons, simulation
$result.risk
```

`simulation` é false. Com contexto recente, dispositivo aprovado, rede classificada, recurso PUBLIC e nenhum login falho na janela completa, score é 0. Isso não concede acesso por si só: ainda precisa de política correspondente e pode haver DENY/MFA/limites.

O contador começa desconhecido nos primeiros cinco minutos do processo e volta ao aquecimento após falha de gravação. Não espere score zero durante esse período: comportamento desconhecido acrescenta 30 pontos. Após a janela completa, zero significa que o produtor observou zero falhas associadas à conta nos últimos cinco minutos. Login válido não apaga as falhas anteriores. Com Authorization desabilitado, esse sinal continua desconhecido.

Contexto expira após cinco minutos sem nova observação; uma nova requisição GET autenticada na cadeia de navegador o atualiza. Pedir evaluate não renova contexto. Sem contextId, com ID de outro usuário, contexto fechado/expirado ou versão antiga da identidade, dispositivo e rede ficam UNKNOWN. Sensibilidade e histórico continuam sendo consultados separadamente. Logout fecha o contexto; mudança de senha/estado invalida a versão observada.

## Revogar e atualizar configurações

```powershell
$currentDevice = Invoke-RestMethod $deviceUrl -Headers $adminHeaders
$revoked = Invoke-RestMethod -Method Put -Uri $deviceUrl `
  -Headers $adminHeaders -ContentType 'application/json' `
  -Body (@{ trusted = $false; ttlSeconds = 600; version = $currentDevice.version } | ConvertTo-Json)

$disabledNetwork = Invoke-RestMethod -Method Put -Uri "$baseUrl/api/admin/risk/networks/$($network.id)" `
  -Headers $adminHeaders -ContentType 'application/json' `
  -Body (@{ enabled = $false; version = $network.version } | ConvertTo-Json)

$currentResource = Invoke-RestMethod "$baseUrl/api/admin/risk/resources?resource=invoices" -Headers $adminHeaders
$updatedResource = Invoke-RestMethod -Method Put -Uri "$baseUrl/api/admin/risk/resources" `
  -Headers $adminHeaders -ContentType 'application/json' `
  -Body (@{ resource = 'invoices'; sensitivity = 'CONFIDENTIAL'; version = $currentResource.version } | ConvertTo-Json)
```

Revogação de aprovação e desativação de rede afetam a coleta seguinte. ttlSeconds continua obrigatório também no pedido de revogação, embora não seja usado para criar um prazo nesse caso. Listar redes: GET `/api/admin/risk/networks`, limitado a cem registros. Configurações são auditadas transacionalmente; falha de auditoria desfaz a alteração. Histórico/contextos não têm expurgo automático nesta entrega; banco/backups contêm dados de origem e requerem a operação prevista na etapa 8.

Para cenários sintéticos, continue usando [simulate da 5B1](abac-risco-local.md), sempre identificado com simulation=true. Próxima entrega: **5C, MFA TOTP e step-up**. A aplicação das decisões no login/tokens/recursos fica na 5D. Consulte [ADR-012](../adr/012-coleta-confiavel-de-risco.md), [evidências](../evidencias/etapa-05b2.md) e [roteiro](../PLANO-DE-IMPLEMENTACAO.md).

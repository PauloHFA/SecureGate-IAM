# Client Credentials local — entrega 4B

Este guia descreve aplicações confidenciais máquina a máquina. Login de usuário e Authorization Code com PKCE estão no [guia da 4C](oauth-pkce-local.md); refresh opcional para clientes web, introspecção e revogação estão no [guia da 4D1](oauth-refresh-local.md). Autenticação OIDC para clientes web está no [guia da 4D2](oidc-local.md).

## Preparar e iniciar

Na raiz do projeto, com JDK 21 e Docker:

```powershell
.\mvnw.cmd -B -ntp clean verify
.\infra\scripts\Initialize-Local.ps1
docker compose --env-file .env -f infra/docker-compose.yml up -d --wait --wait-timeout 180
.\infra\scripts\Start-IamCore.ps1 -EnableAuthorization -EnableBootstrap
```

`-EnableAuthorization` ativa o Transit automaticamente, usando os arquivos AppRole gerados pelo bootstrap Vault. `-EnableBootstrap` habilita a administração necessária para criar um cliente; após provisionar, é possível reiniciar somente com `-EnableAuthorization`. Nenhum cliente é criado automaticamente. O script transmite as configurações ao Java e restaura o ambiente do terminal ao sair.

A migração V3 cria as tabelas JDBC em `iam_authorization` na primeira execução desta versão. O script usa issuer `http://127.0.0.1:8080`; com `-Port 18080`, tanto porta quanto issuer mudam para 18080. Para execução direta do JAR, configurar `IAM_AUTHORIZATION_ENABLED=true`, `IAM_TRANSIT_ENABLED=true` e `IAM_ISSUER`, além dos parâmetros de banco/AppRole do [guia Transit](transit-local.md). Issuer remoto exige HTTPS e não aceita path ou barra final nesta versão.

## Cadastrar um cliente

Em outro terminal PowerShell, na raiz do projeto:

```powershell
. .\infra\scripts\LocalEnvironment.ps1
$settings = Read-LocalEnvironment (Join-Path (Get-Location) '.env')
$baseUrl = 'http://127.0.0.1:8080'
$registration = @{
  clientId = 'local-machine-client'
  name = 'Integracao local'
  audience = 'securegate-api'
  scopes = @('api:read', 'api:write')
} | ConvertTo-Json
$clientResult = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/admin/oauth-clients" `
  -Headers @{ Authorization = 'Bearer ' + $settings.IAM_BOOTSTRAP_TOKEN } `
  -ContentType 'application/json' -Body $registration
$clientResult.client
```

A resposta completa contém `client` e `clientSecret`. O segredo gerado fica em `$clientResult.clientSecret`, só é entregue nessa criação e não pode ser recuperado por GET. Não grave a resposta em arquivos versionados ou logs. Repetir o mesmo `clientId` retorna 409. Para consultar metadados, usar `GET /api/admin/oauth-clients/local-machine-client` com o mesmo Bearer administrativo.

IDs têm 3–64 caracteres minúsculos, números, hífen ou sublinhado, começando com letra. Nome tem até 120 caracteres. Audiência tem até 200 caracteres ASCII de identificador, incluindo `:`, `/`, `.`, `_` e `-`, começando com letra. Até dez escopos de 1–64 caracteres, minúsculos e começando com letra; podem incluir números, `:`, `.`, `_` e `-`. Não usar `openid`, `offline_access`, espaços ou curingas.

## Solicitar token

```powershell
$basicValue = $clientResult.client.clientId + ':' + $clientResult.clientSecret
$basicHeader = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($basicValue))
$tokenResult = Invoke-RestMethod -Method Post -Uri "$baseUrl/oauth2/token" `
  -Headers @{ Authorization = 'Basic ' + $basicHeader } `
  -ContentType 'application/x-www-form-urlencoded' `
  -Body @{ grant_type = 'client_credentials'; scope = 'api:read' }
$tokenResult | Select-Object token_type, expires_in, scope
```

O access token fica em `$tokenResult.access_token`; não o imprima em logs. O header Basic deste exemplo é válido para os IDs e segredos gerados, que não contêm caracteres reservados que precisariam de form encoding. A resposta retorna `token_type=Bearer`, validade próxima de 300 segundos e os escopos solicitados. Não há refresh token ou ID token.

O token tem audiência `securegate-api`, sujeito/client_id `local-machine-client`, escopos limitados ao cadastro e assinatura RS256. O JWKS público fica em `$baseUrl/oauth2/jwks`. A API administrativa continua exigindo seu próprio token bootstrap: este JWT de aplicação não a autoriza. O [recurso local da 5D3](recursos-local.md) atende somente grants de usuário e rejeita Client Credentials. Os testes deste fluxo verificam o JWT com um decoder independente via JWKS.

## Diagnóstico e limites

| Resultado | Verificação |
| --- | --- |
| 401 `invalid_client` | ID/segredo incorreto, autenticação ausente ou método diferente de Basic |
| 400 `invalid_scope` | Enviar `scope` explicitamente, não vazio e como subconjunto do cadastro |
| 400 `unsupported_grant_type` / `invalid_grant` | Grant não implementado ou credencial de grant inválida |
| 503 `temporarily_unavailable` | Verificar Vault, AppRole, política, arquivos e rede; não há fallback local |
| Administração 401/403 | Confirmar `-EnableBootstrap` e Bearer administrativo |
| Token endpoint bloqueado | Confirmar `-EnableAuthorization`; `-EnableTransit` sozinho só habilita JWKS |

Erros OAuth contêm somente `error` e preservam o header `X-Correlation-ID`. Cadastros administrativos usam Problem Details. O token endpoint é stateless e exige formulário URL encoded; a sessão/formulário da 4C pertence à cadeia de navegador, sem autenticar o cliente no token endpoint.

O serviço JDBC padrão persiste tokens e metadados recuperáveis, além de clientes e consentimentos. Banco e backups são sensíveis; a API não expõe esses registros. Rotação de segredo, desativação de clientes, retenção/limpeza e revogação ponta a ponta ainda não estão disponíveis. Introspecção e revogação pelo cliente proprietário estão disponíveis na 4D1. Tokens emitidos duram cinco minutos; a integração de revogação distribuída está prevista no roteiro.

Os testes usam bancos e Vault efêmeros e não alteram o Compose local. Consulte [ADR-006](../adr/006-authorization-server-client-credentials.md), [evidências 4B](../evidencias/etapa-04b.md) e [roteiro](../PLANO-DE-IMPLEMENTACAO.md).

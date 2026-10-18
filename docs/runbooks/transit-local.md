# Assinatura Transit e JWKS — entrega 4A

A entrega 4A fornece o componente de assinatura e JWKS. A 4B acrescenta emissão por Client Credentials; consulte o [guia OAuth](oauth-client-credentials-local.md) para ativá-la. Usar somente `-EnableTransit` mantém o token endpoint bloqueado.

## Iniciar

Na raiz do projeto, com JDK 21 e Docker:

```powershell
.\mvnw.cmd -B -ntp clean verify
.\infra\scripts\Initialize-Local.ps1
docker compose --env-file .env -f infra/docker-compose.yml up -d --wait --wait-timeout 180
.\infra\scripts\Start-IamCore.ps1 -EnableTransit
```

O bootstrap Vault do Compose gera os arquivos `infra/.local/iam-core-role-id` e `infra/.local/iam-core-secret-id`. Se faltarem ou expirarem, consultar o [guia de infraestrutura](../../infra/README.md) e executar novamente o bootstrap. O script verifica a existência deles e transmite seus caminhos absolutos; o Java os lê no login AppRole. Não copie esses valores para arquivos versionados.

`-EnableTransit` é independente de `-EnableBootstrap`; para usar os dois recursos locais, forneça ambos os parâmetros. Sem a opção Transit, JWKS permanece bloqueado e não há conexão com Vault. O `.env` local não precisa receber novas credenciais para a 4A.

Em outro terminal:

```powershell
$jwks = Invoke-RestMethod http://127.0.0.1:8080/oauth2/jwks
$jwks.keys | Select-Object kid, kty, alg, use
```

Esperado: `securegate-jwt-v1`, RSA, RS256 e sig; a versão pode ser maior se já houve rotação. O documento inclui somente parâmetros públicos. `/oauth2/token` continua bloqueado quando só Transit é habilitado.

## Configuração Java

| Propriedade | Padrão / origem |
| --- | --- |
| `iam.transit.enabled` | `IAM_TRANSIT_ENABLED`, false |
| `iam.transit.address` | `IAM_VAULT_ADDRESS`, http://127.0.0.1:8200 |
| `iam.transit.role-id-file` | `IAM_VAULT_ROLE_ID_FILE`, infra/.local/iam-core-role-id |
| `iam.transit.secret-id-file` | `IAM_VAULT_SECRET_ID_FILE`, infra/.local/iam-core-secret-id |
| `iam.transit.key-name` | securegate-jwt |
| `iam.transit.timeout` | 2s por chamada HTTP, faixa 100ms–10s |
| `iam.transit.public-key-cache-ttl` | 30s, faixa 1s–5min |
| `iam.transit.max-concurrent-requests` | 8, faixa 1–64 |

Ao executar o JAR diretamente, os caminhos relativos são resolvidos pelo diretório de trabalho. Para ambientes remotos use HTTPS e monte os arquivos de credenciais fora da imagem. O cliente não segue redirects.

## Rotação e diagnóstico

A aplicação não tem permissão de rotação. Use uma identidade operacional autorizada pela política `infra/vault/key-operator.hcl` para chamar `POST /v1/transit/keys/securegate-jwt/rotate`. Aguarde o TTL do cache interno e confirme a nova versão e a anterior no JWKS. Não remova versões nem altere a versão mínima durante essa janela. Os testes executam a rotação somente em seu container efêmero; o Vault de desenvolvimento não é alterado pelo build.

Se o Vault parar, novas assinaturas falham. O JWKS funciona até expirar seu cache interno; depois retorna HTTP 503 sanitizado, `Retry-After: 5` e `X-Correlation-ID`. Conferir conectividade, política, TTL do SecretID e montagem dos arquivos. O erro não inclui a resposta privada do Vault. Não habilite logs de headers HTTP, tokens ou corpos de login.

O token AppRole fica na memória até perto da expiração, quando a aplicação autentica novamente usando os arquivos. SecretID expirado exige novo bootstrap local ou entrega operacional de um novo arquivo. Renovação de leases e credenciais dinâmicas pertencem à etapa 9.

Métricas internas: `iam.transit.requests` mede chamadas por `operation=login|keys|sign` e `outcome=success|failure`; `iam.transit.rejected` conta excesso de concorrência. A exposição pública do Actuator continua limitada à saúde. Consulte [ADR-005](../adr/005-transit-e-jwks.md) e [evidências](../evidencias/etapa-04.md).

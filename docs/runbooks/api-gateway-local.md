# Runbook — API Gateway e Roteamento de Borda com Validação Redis

- **Público:** Engenheiros de Backend, DevOps e SREs
- **Objetivo:** Operação, configuração e diagnóstico do serviço de borda **SecureGate API Gateway** (módulo `api-gateway`).
- **Porta Padrão:** `8082` (Gateway) roteando para `8080` (IAM Core).

---

## 1. Visão Geral da Arquitetura do Gateway

O **SecureGate API Gateway** atua como ponto único de entrada para o tráfego HTTP/REST do ecossistema, separando o tráfego de controle e metadados das chamadas a APIs protegidas de negócio:

```
[ Cliente HTTP / SPA / Frontend ]
               |
               v Porta 8082
[ SecureGate API Gateway (Spring Cloud Gateway) ]
       |                            |
       | (Consulta Revocação)       | (Roteamento Seguro)
       v                            v Porta 8080
 [ Redis Cache ]             [ IAM Core Backend ]
 (Projeções em Tempo Real)    - /oauth2/*, /.well-known/*
                              - /api/resources/*, /api/pam/*
```

### 1.1. Categorias de Rotas
1. **Rotas Públicas e de Autenticação (Passthrough):**
   - Discovery OIDC: `GET /.well-known/openid-configuration`
   - Chaves JWKS: `GET /oauth2/jwks`
   - Emissão de Tokens: `POST /oauth2/token`
   - Autorização e Consentimento: `GET /oauth2/authorize`, `POST /oauth2/authorize`
   - Autenticação e MFA: `/login`, `/mfa`
2. **Rotas Protegidas com Interceptação de Borda:**
   - APIs de Recursos: `GET /api/resources/**`
   - APIs de Gestão Privilegiada (PAM): `/api/pam/**`
   - Aplicam o filtro `TokenRevocationGatewayFilter` antes de encaminhar ao backend.

---

## 2. Política de Revogação de Borda e Fail-Closed

### 2.1. Regras de Decisão do Filtro
Quando uma requisição atinge `/api/resources/**` ou `/api/pam/**`:
1. **Extração de Bearer Token:** O cabeçalho `Authorization: Bearer <jwt>` é inspecionado. Ausência resulta em `HTTP 401 Unauthorized` imediato com Problem JSON (RFC 7807).
2. **Extração de Claims:** São extraídos o identificador do token (`jti`), a sessão (`sid`) e a conta (`sub`).
3. **Consulta de Projeções no Redis:**
   O Gateway consulta simultaneamente as chaves atômicas:
   - `securegate:{revocation}:jti:<sha256(jti)>`
   - `securegate:{revocation}:sid:<sha256(sid)>`
   - `securegate:{block-policy}:blocked:account:<sha256(sub:1)>`
4. **Rejeição Precoce:** Se qualquer chave existir no Redis, o Gateway recusa a requisição com `HTTP 401 Unauthorized`, poupando CPU e banco de dados do IAM Core.
5. **Propagação de Confirmação:** Se nenhuma chave for encontrada, o cabeçalho `X-Gateway-Verified: true` é injetado e a requisição segue para o backend.

### 2.2. Postura Fail-Closed
Em caso de falha de conexão, timeout ou indisponibilidade da instância do Redis:
- Configuração padrão: `securegate.gateway.fail-closed: true`.
- Comportamento: O Gateway rejeita preventivamente com `HTTP 503 Service Unavailable`:
  ```json
  {
    "type": "about:blank",
    "title": "Service Unavailable",
    "status": 503,
    "detail": "Security policy projection temporarily unavailable (Fail-Closed)",
    "instance": "/api/resources/account"
  }
  ```
- **Garantia de Segurança:** O acesso a dados protegidos nunca é permitido às cegas enquanto o estado de revogação for desconhecido.

---

## 3. Comandos de Execução Local

### 3.1. Execução via Maven
Para iniciar o gateway localmente:
```powershell
.\mvnw.cmd -B -ntp -pl :api-gateway spring-boot:run
```

### 3.2. Execução via Docker Compose
Para subir o Gateway junto com a infraestrutura:
```powershell
docker compose -f infra/docker-compose.yml --profile gateway up -d
```
Ou com a stack completa:
```powershell
docker compose -f infra/docker-compose.yml --profile full up -d
```

---

## 4. Teste de Roteamento e Revogação

1. **Consulta de Discovery OIDC via Gateway:**
   ```bash
   curl -s -f http://localhost:8082/.well-known/openid-configuration | jq .
   ```
2. **Chamada a Recurso Protegido sem Token (Rejeição esperada 401):**
   ```bash
   curl -s -w "\nStatus: %{http_code}\n" http://localhost:8082/api/resources/account
   ```
3. **Chamada com Token Ativo:**
   ```bash
   curl -s -H "Authorization: Bearer $VALID_TOKEN" http://localhost:8082/api/resources/account
   ```

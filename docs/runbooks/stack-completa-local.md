# Runbook — Execução da Stack Completa com Docker Compose (Etapa 11A)

Este runbook orienta a construção, inicialização e verificação de integridade da stack completa do SecureGate IAM utilizando imagens multi-stage e o perfil Compose `full`.

---

## 1. Visão Geral da Topologia da Stack

A stack completa (`--profile full`) orquestra sete componentes integrados na mesma rede Docker:

| Serviço | Imagem | Porta Host | Descrição / Papel |
| --- | --- | --- | --- |
| `postgres` | `postgres:16.15-alpine` | `15432` | Banco relacional particionado para identidade, acessos e auditoria. |
| `redis` | `redis:7.4.11-alpine` | `6379` | Cache de alta velocidade para revogação de tokens e políticas. |
| `rabbitmq` | `rabbitmq:4.3.6-management-alpine` | `5672` / `15672` | Mensageria AMQP e outbox transacional assíncrono. |
| `vault` | `hashicorp/vault:1.21.4` | `8200` | Cofre de segredos, Transit signing e AppRoles. |
| `vault-init` | `hashicorp/vault:1.21.4` | — | Job efêmero de bootstrap e provisionamento de chaves Transit/AppRole. |
| `iam-core` | `securegate/iam-core:0.1.0-snapshot` | `8080` | Servidor de Autorização OAuth2/OIDC e Gestão de Identidades. |
| `policy-enforcer` | `securegate/policy-enforcer:0.1.0-snapshot` | `19091` | Consumidor AMQP de revogações e enforcer de políticas. |

---

## 2. Pré-requisitos

- Docker Desktop ou Docker Engine com plugin Compose v2 ativo.
- Portas disponíveis no host: `8080`, `15432`, `6379`, `5672`, `15672`, `8200`.
- PowerShell 5.1+ (Windows) ou PowerShell 7+ (`pwsh` no Linux/macOS).

---

## 3. Passo a Passo de Execução

### Passo 1 — Preparar o ambiente local
Gera credenciais seguras e arquivos de configuração no diretório ignorado `infra/.local/`:
```powershell
pwsh infra/scripts/Initialize-Local.ps1
```

### Passo 2 — Subir a stack completa com build
```powershell
docker compose -f infra/docker-compose.yml --profile full up -d --build
```
> O Docker Compose gerencia automaticamente a ordem de inicialização:
> 1. Inicia `postgres`, `redis`, `rabbitmq` e `vault`.
> 2. Aguarda a prontidão dos healthchecks dessas 4 dependências.
> 3. Executa o container `vault-init` para criar as chaves Transit e AppRoles.
> 4. Inicia `iam-core` após o sucesso do `vault-init`.
> 5. Inicia `policy-enforcer` após o `iam-core` estar saudável (`readiness: UP`).

### Passo 3 — Verificar o status dos containers
```powershell
docker compose -f infra/docker-compose.yml --profile full ps
```
Todos os serviços devem exibir status `running` e `healthy` (com exceção do `vault-init` que encerra com status `exited (0)`).

### Passo 4 — Executar o Smoke Test Automatizado
```powershell
pwsh infra/scripts/Test-FullStack.ps1 -BaseUri http://127.0.0.1:8080
```
O script valida sucessivamente:
1. Probe de readiness: `GET /actuator/health/readiness` -> HTTP 200 `UP`.
2. Probe de liveness: `GET /actuator/health/liveness` -> HTTP 200 `UP`.
3. OIDC Discovery: `GET /.well-known/openid-configuration` -> HTTP 200 (valida issuer e endpoints).
4. Conjunto de chaves públicas JWKS: `GET /oauth2/jwks` -> HTTP 200 (chaves ativas ECDSA).
5. Contrato de recusa de token não autenticado: `POST /oauth2/token` -> HTTP 400/401.

---

## 4. Encerrando e Limpando a Stack

Para parar os containers preservando dados em volumes:
```powershell
docker compose -f infra/docker-compose.yml --profile full down
```

Para remover completamente os volumes e reiniciar do zero:
```powershell
docker compose -f infra/docker-compose.yml --profile full down -v
```

# Runbook — Instalação Limpa e Procedimento de Release v1.0.0

- **Público:** Engenheiros de Operações, SREs e Desenvolvedores
- **Objetivo:** Procedimento passo a passo para inicialização da stack completa SecureGate IAM a partir de um ambiente limpo (*cold start*), validação operacional e procedimento de tagueamento de release `v1.0.0`.
- **Ambiente:** Linux (Ubuntu 22.04+), macOS ou Windows (com Docker Desktop / WSL2)

---

## 1. Pré-requisitos de Ambiente

Para executar a stack completa a partir de uma instalação limpa, o ambiente host deve dispor de:
- **Docker Engine:** Versão `24.0.0+` com suporte a `docker compose` v2.
- **Recursos Mínimos Recomendados:**
  - CPU: 2 núcleos dedicados ou virtuais
  - Memória RAM: 4 GB livres
  - Armazenamento: 10 GB livres em disco (para imagens base e volumes PostgreSQL/Vault/Redis)
- **Portas de Rede Livres no Host:**
  - `8080`: IAM Core HTTP (OAuth 2.1 / OIDC / Actuator)
  - `5432`: PostgreSQL transacional (se exposta externamente)
  - `8200`: HashiCorp Vault (Transit / KV)
  - `6379`: Redis cache
  - `5672` / `15672`: RabbitMQ broker e dashboard de gerenciamento

---

## 2. Passo a Passo: Instalação Limpa (*Cold Start*)

### Passo 2.1: Obtenção do Repositório e Preparação
Em uma máquina limpa com Docker instalado:
```bash
git clone https://github.com/PauloHFA/SecureGate-IAM.git
cd SecureGate-IAM
```

### Passo 2.2: Configuração de Variáveis de Ambiente
Copie o template de variáveis de ambiente para o arquivo `.env` (se ainda não existir):
```bash
cp infra/.env.example infra/.env 2>/dev/null || true
```
> [!NOTE]
> Os valores padrão declarados no `infra/docker-compose.yml` são seguros para execução local e já contêm credenciais de desenvolvimento pré-configuradas para o bootstrap do Vault e PostgreSQL.

### Passo 2.3: Inicialização da Stack com Perfil `full`
Execute o comando Docker Compose ativando o perfil `full`:
```bash
docker compose -f infra/docker-compose.yml --profile full up -d --build
```

O Compose orquestra a inicialização em ordem estrita de dependências:
1. `postgres`, `redis`, `rabbitmq`, `vault`: Sobem e aguardam seus healthchecks ficarem `healthy`.
2. `vault-init`: Conecta ao Vault, inicializa os segredos KV, as chaves do Transit e gera os AppRoles montados em `/etc/securegate/`. O container finaliza com código 0 (`service_completed_successfully`).
3. `iam-core` e `policy-enforcer`: Iniciam após a prontidão de todos os serviços de infraestrutura e do `vault-init`.

### Passo 2.4: Acompanhamento da Prontidão (Healthcheck Readiness)
Monitore a prontidão do `iam-core` através do endpoint Actuator:
```bash
# Aguardar status UP no endpoint de readiness
curl -s http://localhost:8080/actuator/health/readiness | grep -q "UP" && echo "Stack Pronta!"
```

---

## 3. Validação Operacional e Smoke Test

Execute o script automatizado de smoke test para validar a integridade da stack recém-instalada:

No Windows (PowerShell):
```powershell
.\infra\scripts\Test-FullStack.ps1 -BaseUrl "http://localhost:8080"
```

No Linux/macOS (Bash):
```bash
# 1. Healthcheck Geral
curl -s -f http://localhost:8080/actuator/health | jq .

# 2. Discovery OIDC
curl -s -f http://localhost:8080/.well-known/openid-configuration | jq .

# 3. Conjunto de Chaves JWKS
curl -s -f http://localhost:8080/oauth2/jwks | jq .

# 4. Contrato Negativo de Token (Rejeição esperada HTTP 400 sem credenciais)
curl -s -o /dev/null -w "%{http_code}\n" -X POST http://localhost:8080/oauth2/token
# Saída esperada: 400
```

---

## 4. Procedimento de Encerramento e Limpeza

Para parar a stack preservando os volumes de dados:
```bash
docker compose -f infra/docker-compose.yml --profile full down
```

Para limpeza total (*clean wipe*), removendo volumes e redes:
```bash
docker compose -f infra/docker-compose.yml --profile full down -v
```

---

## 5. Procedimento de Tagueamento e Publicação de Release v1.0.0

A publicação oficial das imagens no GitHub Container Registry (`ghcr.io`) é acionada automaticamente pelo workflow [`.github/workflows/release.yml`](../../.github/workflows/release.yml) quando uma tag semântica `v*` é enviada para a branch `main`:

1. **Certificar que o PR da Etapa 11D foi aprovado e integrado na `main`:**
   ```bash
   git checkout main
   git pull origin main
   ```
2. **Criar a tag anotada oficial da versão v1.0.0:**
   ```bash
   git tag -a v1.0.0 -m "Release v1.0.0: SecureGate IAM Core & Policy Enforcer official release"
   ```
3. **Enviar a tag para o repositório remoto:**
   ```bash
   git push origin v1.0.0
   ```
4. **Acompanhar a Publicação GHCR:**
   - Acesse `https://github.com/PauloHFA/SecureGate-IAM/actions/workflows/release.yml`.
   - O pipeline compila imagens multi-arquitetura (`linux/amd64`, `linux/arm64`) e as publica em:
     - `ghcr.io/paulohfa/securegate-iam-core:v1.0.0` e `ghcr.io/paulohfa/securegate-iam-core:latest`
     - `ghcr.io/paulohfa/securegate-policy-enforcer:v1.0.0` e `ghcr.io/paulohfa/securegate-policy-enforcer:latest`

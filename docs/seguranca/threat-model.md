# Modelo de Ameaças (Threat Model) — SecureGate IAM

- **Versão:** 1.0.0
- **Data:** 23/10/2026
- **Metodologia:** STRIDE (Spoofing, Tampering, Repudiation, Information Disclosure, Denial of Service, Elevation of Privilege)
- **Classificação:** Documento Técnico de Segurança / Arquitetura
- **Escopo:** IAM Core, Policy Enforcer, Endpoints OAuth/OIDC, Vault Transit / KV, PostgreSQL, Redis, RabbitMQ

---

## 1. Visão Geral da Arquitetura e Fronteiras de Confiança

O **SecureGate IAM** implementa uma arquitetura de controle de acesso Zero Trust baseada em microsserviços e bibliotecas compartilhadas, estruturada em zonas e fronteiras de confiança estritas:

```
[ Usuários / Navegadores / SPAs ] (Fronteira Externa - Não Confiável)
               |
               v HTTPS / TLS 1.3
[ Borda / Reverse Proxy / Ingress ]
               |
               v Rede Interna Isolada (VPC / Overlay Docker Network)
      +--------+------------------------+
      |                                 |
      v                                 v
[ IAM Core (Porta 8080) ]     [ Policy Enforcer ]
   - Identity & Access            - Revocation Consumer
   - OAuth 2.1 / OIDC             - Policy Consumer
   - Engine ABAC/RBAC             - Redis Cache Projections
   - PAM Engine
      |         \                 /          |
      |          \               /           |
      v           v             v            v
[ PostgreSQL ]  [ HashiCorp Vault ]  [ Redis ]  [ RabbitMQ ]
 (Transacional)   (Transit / KV)      (Cache)     (Eventos)
```

### Fronteiras de Confiança (Trust Boundaries):
1. **TB-1: Fronteira Externa vs. IAM Core:** Clientes HTTP (navegadores, aplicações cliente, atacantes em potencial) interagindo com `/oauth2/*`, `/login`, `/mfa`, `/api/resources/*`, `/api/admin/*`.
2. **TB-2: Aplicação vs. Armazenamento Transacional (PostgreSQL):** Conexões JDBC autenticadas via usuário dedicado com privilégios mínimos (`iam_app`), restrições de schema e isolamento transacional.
3. **TB-3: Aplicação vs. Módulo Criptográfico / Cofre (HashiCorp Vault):** Comunicação HTTP/REST autenticada por AppRole montado em arquivo somente-leitura; tokens de curta duração (1h); segregação entre chaves Transit (RS256 para JWTs, AES-256-GCM para sementes MFA) e segredos KV v2.
4. **TB-4: Aplicação vs. Mensageria Assíncrona (RabbitMQ):** Troca de eventos de segurança via outbox transacional garantindo entrega confiável at-least-once com payload imutável versionado (envelope v1).
5. **TB-5: Aplicação vs. Projeção Distribuída em Cache (Redis):** Cache local de revogações e bloqueios consultado com fail-closed em caso de inacessibilidade.

---

## 2. Inventário de Ativos Críticos

| Ativo | Descrição | Requisito de Segurança |
| --- | --- | --- |
| **Chave Privada de Assinatura JWT** | Chave assimétrica RSA (2048+ bits) mantida no Vault Transit | Confidencialidade absoluta (nunca exportada do Vault), integridade |
| **Credenciais de Usuários** | Senhas corporativas | Hash Argon2id ($m=19456, t=2, p=1$), salt criptográfico |
| **Sementes MFA (TOTP)** | Segredos base32 TOTP dos usuários | Criptografia AES-256-GCM envelopada no Vault Transit vinculada a UID |
| **Tokens de Acesso / Refresh** | JWTs RS256 e hashes SHA-256 de refresh tokens | Curta validade (5 min para access token; 30 min absoluto para refresh) |
| **Políticas de Autorização (ABAC)** | Regras JSONB versionadas | Integridade estrita, denegação por padrão (*Deny-by-default*) |
| **Trilhas de Auditoria e Eventos** | Registros de tentativas de autenticação e ações administrativas | Imutabilidade, não-repúdio, partição mensal com retenção |
| **Credenciais de Infraestrutura** | AppRole secret-id, senhas de banco, broker e cache | Não persistidos em Git, montados via arquivos read-only ou variáveis |

---

## 3. Análise de Ameaças STRIDE

### 3.1. Spoofing (Falsificação de Identidade)
- **T-S1: Falsificação de Token JWT (Forged Tokens):** Atacante gera tokens falsos assinados com chaves próprias ou algoritmo `none`.
  - *Mitigação:* Validação estrita do cabeçalho `alg=RS256`; chaves públicas obtidas exclusivamente do JWKS oficial autenticado; validação criptográfica no Vault Transit ou via Nimbus JWT Decoder com clock skew controlado (< 60 s).
- **T-S2: Reuso de Refresh Token por Terceiros (Token Theft):** Interceptação de refresh token na rede.
  - *Mitigação:* Rotação obrigatória a cada uso; invalidação em cascata da família inteira de tokens caso reuso seja detectado; amarração estrita ao `client_id` proprietário.
- **T-S3: Interceptação de Código de Autorização OAuth (Authorization Code Interception):** Interceptação do parâmetro `code` no redirecionamento.
  - *Mitigação:* Exigência mandatória de PKCE com desafio SHA-256 (`code_challenge_method=S256`) em todos os fluxos de autorização para clientes confidenciais e públicos.
- **T-S4: Bypass de Credencial por Timing Attack:** Enumeração de usuários ou inferência de hash de senha através de discrepâncias temporais.
  - *Mitigação:* Execução obrigatória de cálculo Argon2id com hash sintético mesmo quando o usuário não existe (`JdbcLoginUsers`), garantindo resposta com tempo constante.

### 3.2. Tampering (Adulteração de Dados)
- **T-T1: Adulteração de Políticas ABAC no Banco de Dados:** Modificação não autorizada de regras de acesso em `iam_policy`.
  - *Mitigação:* Princípio do menor privilégio no PostgreSQL (`iam_app` não possui privilégios de DDL); trigger de auditoria transacional; verificação de integridade e assinatura de eventos de mudança de política.
- **T-T2: Alteração de Contexto de Risco ou Header de Rede:** Forjamento de headers como `X-Forwarded-For` para mascarar IP do invasor.
  - *Mitigação:* Sanitização e aceitação de headers de proxy reverso somente sob configuração explícita de proxies confiáveis (`server.forward-headers-strategy=framework`).
- **T-T3: Injeção de SQL nos Endpoints Administrativos:** Submissão de SQL malicioso via campos de busca/filtros.
  - *Mitigação:* Utilização exclusiva de consultas parametrizadas via Spring Data JPA / `JdbcTemplate` com prepared statements; validação rigorosa de entrada com regex fechadas para UUIDs e identificadores.

### 3.3. Repudiation (Repúdio de Ações)
- **T-R1: Negação de Emissão de Credenciais Privilegiadas (PAM):** Administrador nega ter solicitado ou consumido acesso privilegiado.
  - *Mitigação:* Trilha de auditoria transacional gravada no mesmo commit do banco (`outbox`); correlação ponta a ponta via `traceId`, `correlationId`, carimbo de tempo UTC e identificador de ator auditado.
- **T-R2: Negação de Bloqueio ou Revogação de Acesso:** Usuário alega que não teve o token revogado.
  - *Mitigação:* Emissão síncrona de evento `auth.token.revoked` com gravação na base e emissão imediata para barramento RabbitMQ, projetando estado no Redis com timestamp auditável.

### 3.4. Information Disclosure (Divulgação Indevida de Informações)
- **T-I1: Vazamento de Segredos em Logs Operacionais:** Tokens JWT, chaves de API, senhas ou sementes MFA gravados nos arquivos de log.
  - *Mitigação:* Perfil `structured-logs` com catálogo fechado de templates; mascaramento determinístico (SHA-256); exclusão completa de mensagens de exceção brutas, headers sensíveis e parâmetros de query string.
- **T-I2: Exposição de Stack Trace em Erros HTTP:** Ataque de exploração de vulnerabilidades a partir de stack traces detalhados retornados na API.
  - *Mitigação:* Tratamento centralizado de erros em conformidade com RFC 7807 (Problem Details), omitindo stack traces, detalhes de SQL ou classes internas do framework.
- **T-I3: Vazamento de Metadados em Ambientes de Container:** Imagens de container contendo código fonte, arquivos `.git` ou chaves privadas.
  - *Mitigação:* Builds multi-stage limpos (`eclipse-temurin:21-jre-alpine`); `.dockerignore` rigoroso; scan automatizado com Aquasecurity Trivy quebrando o CI em vulnerabilidades `CRITICAL` e `HIGH`.

### 3.5. Denial of Service (Negação de Serviço)
- **T-D1: Ataque de Força Bruta contra Autenticação:** Disparo em massa de tentativas de login visando exaustão de CPU (custo de Argon2id) ou lockout de contas legítimas.
  - *Mitigação:* Filtro distribuído de controle de tentativas (`DistributedLoginFilter`); bloqueio progressivo por IP e usuário; limitação de requisições simultâneas.
- **T-D2: Esgotamento de Recursos no Vault Transit:** Inundação de requisições de assinatura `/oauth2/token` esgotando conexões com o Vault.
  - *Mitigação:* Semáforo estrito de concorrência (`IAM_TRANSIT_MAX_CONCURRENT_REQUESTS`, default 30); pool HTTP resiliente com timeouts curtos e circuito fail-fast.
- **T-D3: Slowloris e Esgotamento de Conexões Tomcat:** Conexões HTTP mantidas abertas deliberadamente com envio lento de bytes.
  - *Mitigação:* Limite de conexões ativas (`server.tomcat.max-connections=10000`), timeout de conexão (`server.tomcat.connection-timeout=5000ms`), threads de execução controladas (`server.tomcat.threads.max=200`).

### 3.6. Elevation of Privilege (Elevação de Privilégios)
- **T-E1: Acesso Indevido a Endpoints Administrativos (`/api/admin/**`):** Usuário comum tenta invocar endpoints administrativos de gestão de usuários ou políticas.
  - *Mitigação:* Bloqueio total por padrão (`anyRequest().denyAll()`); isolamento do bootstrap admin exigindo autoridade explícita `BOOTSTRAP_ADMIN` e cabeçalho `X-Bootstrap-Token` com chave de alta entropia.
- **T-E2: Bypass de Políticas ABAC via Combinação de Regras:** Tentativa de explorar regras permissivas ou colisões de atributos para obter autorização não intencional.
  - *Mitigação:* Princípio de *Deny-Overrides*: qualquer regra DENY cancela avaliações permissivas; avaliação estrita de tipo; validação de contexto e temporalidade no motor de políticas.
- **T-E3: Execução de Processos em Container como Root:** Comprometimento do container resultando em controle do host do Docker.
  - *Mitigação:* Execução exclusiva sob usuário sem privilégios (`securegate`, UID/GID 10001); sistema de arquivos montado com privilégios mínimos.

---

## 4. Matriz de Risco Residual e Ações

| ID Ameaça | Severidade Bruta | Controles Mitigantes | Risco Residual | Ação Operacional |
| --- | --- | --- | --- | --- |
| **T-S1** (Forged JWT) | Crítica | RS256 no Vault Transit, JWKS estrito | Baixo | Monitorar expiração de chaves e rotação anual |
| **T-S2** (Theft Refresh) | Alta | Rotação imediata, família com revogação em cascata | Baixo | Alertar eventos `auth.refresh.reuse` via SIEM |
| **T-I1** (Log Leaks) | Crítica | Logs estruturados JSON, catálogo fechado, no free text | Muito Baixo | Auditoria de SAST contínua com CodeQL e GitGuardian |
| **T-D1** (Brute-force) | Alta | Argon2id, lockout distribuído, rate-limit | Médio | Implantar WAF / Edge Rate Limiter em produção |
| **T-E1** (Admin Bypass) | Crítica | Bootstrap desabilitado por padrão, deny-all, RBAC | Muito Baixo | Token de bootstrap nunca deve ser fixado em código |

# Visão Geral da Arquitetura do SecureGate IAM

- **Status:** Consolidado (Versão 1.0.0 + Gateway de Borda)
- **Data:** 25/10/2026
- **Escopo:** Mapeamento C4 (Contexto, Contêineres, Componentes e Fluxo de Dados) de todo o ecossistema.

---

## 1. Nível 1: Diagrama de Contexto de Sistema (C4 Context)

O SecureGate IAM é uma plataforma corporativa Zero Trust de Gestão de Identidade, Acesso e Autorização Contínua, projetada para desacoplar custódia criptográfica, decisão de políticas e aplicação distribuída.

```mermaid
flowchart TD
    subgraph Atores["Atores e Clientes Externos"]
        User["Usuário / Navegador<br/>(SPA, Web, Mobile)"]
        ServiceClient["Cliente de Máquina<br/>(Microsserviços / M2M)"]
        Admin["Administrador de Segurança<br/>(SecOps / Auditoria)"]
        Operator["Operador de Infraestrutura<br/>(SRE / DevOps)"]
    end

    subgraph SecureGateIAM["Plataforma SecureGate IAM"]
        Gateway["SecureGate API Gateway<br/>(Borda Reativa Spring Cloud)"]
        Core["SecureGate IAM Core<br/>(Identidade, Acesso, Políticas, PAM)"]
        Enforcer["SecureGate Policy Enforcer<br/>(Aplicação Assíncrona de Políticas)"]
    end

    subgraph Dependencias["Infraestrutura de Apoio e Cofre"]
        Vault["HashiCorp Vault<br/>(Transit, PKI, Segredos Dinâmicos)"]
        Postgres[("PostgreSQL 16<br/>(Auditoria Particionada, Schemas IAM)")]
        RabbitMQ["RabbitMQ Broker<br/>(Mensageria de Eventos de Segurança)"]
        Redis[("Redis 7 Cache<br/>(Projeções de Revogação e Bloqueios)")]
        Observability["Observabilidade Centralizada<br/>(Prometheus, Grafana, Tempo, OTLP)"]
    end

    User -->|"HTTPS / OAuth2 PKCE / Login"| Gateway
    ServiceClient -->|"HTTPS / Client Credentials"| Gateway
    Admin -->|"HTTPS / Bootstrap Admin / PAM"| Gateway
    Operator -->|"Prometheus / Grafana / Dashboards"| Observability

    Gateway -->|"Roteamento Seguro & Fail-Closed"| Core
    Gateway -->|"Consulta Rápida de Revogação"| Redis
    Core -->|"Assinatura RS256 & Criptografia"| Vault
    Core -->|"Persistência Transacional & Outbox"| Postgres
    Core -->|"Publicação Confiável com Confirms"| RabbitMQ
    Enforcer -->|"Consumo de Eventos de Segurança"| RabbitMQ
    Enforcer -->|"Projeção Atômica de Revogação"| Redis
    Core -.->|"Métricas e Tracing OTLP"| Observability
    Enforcer -.->|"Métricas e Tracing OTLP"| Observability
    Gateway -.->|"Métricas e Logs"| Observability
```

---

## 2. Nível 2: Diagrama de Contêineres (C4 Container)

Detalhamento dos nós computacionais, portas, protocolos e responsabilidades de comunicação:

```mermaid
flowchart LR
    subgraph Borda["Camada de Borda (Edge)"]
        GW["API Gateway<br/>(Spring Cloud Gateway / Netty)<br/>Porta: 8082"]
    end

    subgraph CoreDomain["Domínio Central"]
        IAM["IAM Core<br/>(Spring Boot 3 / Tomcat)<br/>Porta: 8080"]
        ENF["Policy Enforcer<br/>(Java 21 Worker / Non-web)<br/>Métricas: 18081"]
    end

    subgraph StateAndSecrets["Armazenamento e Segredos"]
        VLT["HashiCorp Vault<br/>(Porta 8200)<br/>Transit, KV v2, DB Engine"]
        PG[("PostgreSQL 16<br/>(Porta 15432)<br/>Schemas: identity, access, policy,<br/>authorization, audit, pam")]
        RMQ["RabbitMQ<br/>(Portas: 5672 AMQP, 15672 UI)<br/>Exchange: iam.security.events"]
        RDS[("Redis 7<br/>(Porta 6379)<br/>Chaves: securegate:{revocation|block-policy}")]
    end

    GW -->|"HTTP / REST<br/>X-Gateway-Verified"| IAM
    GW -->|"RESP3 / Reactive Lettuce<br/>Projeções Wire-Key"| RDS

    IAM -->|"HTTPS / Vault AppRole<br/>Assinatura Transit sem extração"| VLT
    IAM -->|"JDBC / HikariCP TLS<br/>Transações Atômicas & Outbox"| PG
    IAM -->|"AMQP 0-9-1 Publisher Confirms<br/>Mandatory Return"| RMQ

    ENF -->|"AMQP 0-9-1 Consumer<br/>Manual ACK & Prefetch 10"| RMQ
    ENF -->|"RESP3 / RedisTemplate<br/>Scripts Lua Atômicos"| RDS
```

---

## 3. Nível 3: Arquitetura de Componentes do IAM Core (C4 Component)

O `iam-core` adota arquitetura hexagonal estrita orientada a contextos delimitados (DDD):

```mermaid
flowchart TD
    subgraph Presentation["Camada de Apresentação (Inbound Adapters)"]
        LoginCtrl["LoginController<br/>(/login, /mfa)"]
        OAuthCtrl["OAuthClientController<br/>(/oauth2/token, /oauth2/authorize)"]
        ResourceCtrl["ResourceController<br/>(/api/resources/account)"]
        PamCtrl["PamCredentialController<br/>(/api/pam/requests)"]
        AdminCtrl["UserController / PolicyController<br/>(/api/admin/**)"]
        AuditCtrl["AuditQueryController<br/>(/api/admin/audit/events)"]
    end

    subgraph Application["Camada de Aplicação (Ports & Use Cases)"]
        TokenSvc["TokenAccessService"]
        ResourceSvc["ResourceAccessService"]
        PamSvc["PamCredentialService"]
        PolicyEngine["PolicyEvaluationService"]
        OutboxPublisher["TransactionalOutboxRelay"]
        AuditQuerySvc["AuditQueryService"]
    end

    subgraph Domain["Camada de Domínio (Pure Java 21)"]
        UserAggregate["User & Credential (Argon2id)"]
        PolicyModel["ABAC & Decision Table"]
        MfaFactor["MFA TOTP & Provas"]
        PamGrant["Privileged Access Grant"]
        SecurityEventModel["SecurityEvent Contract v1"]
    end

    subgraph Infrastructure["Camada de Infraestrutura (Outbound Adapters)"]
        TransitAdapter["VaultTransitSigningKeys"]
        JdbcOutbox["JdbcOutboxRepository"]
        JdbcAudit["PartitionedAuditTrail"]
        RedisEnforce["RedisResourceEnforcement"]
        RabbitAdapter["RabbitMqEventPublisher"]
    end

    Presentation --> Application
    Application --> Domain
    Application --> Infrastructure
    Infrastructure -.-> Domain
```

---

## 4. Fluxo de Decisão Zero Trust e Revogação Contínua

Demonstração do ciclo de vida completo de uma requisição com verificação precoce em borda e propagação assíncrona de revogação:

```mermaid
sequenceDiagram
    autonumber
    actor Cliente as Cliente HTTP
    participant GW as API Gateway (:8082)
    participant Redis as Redis Cache (:6379)
    participant Core as IAM Core (:8080)
    participant Bus as RabbitMQ (:5672)
    participant Enforcer as Policy Enforcer (:18081)

    Note over Cliente,Core: 1. Emissão de Token e Acesso Legítimo
    Cliente->>Core: POST /oauth2/token (client_credentials ou authorization_code)
    Core-->>Cliente: 200 OK [JWT assinado RS256 via Transit com jti, sid, sub]
    Cliente->>GW: GET /api/resources/account [Bearer Token]
    GW->>Redis: Consulta chaves canônicas (jti, sid, blocked:account)
    Redis-->>GW: Ausente (Válido / Não revogado)
    GW->>Core: Repassa requisição com cabeçalho X-Gateway-Verified: true
    Core-->>GW: 200 OK [Payload da Conta]
    GW-->>Cliente: 200 OK [Payload da Conta]

    Note over Cliente,Enforcer: 2. Incidente de Segurança ou Revogação
    Cliente->>Core: POST /oauth2/revoke ou Bloqueio Administrativo
    Core->>Core: Grava outbox transacional com evento security.token.revoked
    Core->>Bus: Publica SecurityEvent no Exchange com Confirms
    Bus->>Enforcer: Entrega mensagem para o worker com prefetch controlado
    Enforcer->>Redis: Executa script Lua e projeta securegate:{revocation}:jti:<hash>
    Enforcer-->>Bus: Manual ACK confirmando persistência no Redis

    Note over Cliente,GW: 3. Recusa Imediata na Borda (< 2s)
    Cliente->>GW: GET /api/resources/account [Mesmo Bearer Token]
    GW->>Redis: Consulta chaves canônicas no Redis
    Redis-->>GW: Chave Presente! (Revogado)
    GW-->>Cliente: 401 Unauthorized (RFC 7807 Problem Details)
    Note over GW: Bloqueio realizado na borda sem consumir recursos do IAM Core!
```

---

## 5. Diretrizes de Resiliência e Fail-Closed

1. **Falha de Dependência Redis no Gateway:** O Gateway adota estritamente *Fail-Closed*. Se o Redis estiver indisponível ou ocorrer timeout de rede, a requisição é recusada imediatamente com `HTTP 503 Service Unavailable`, impedindo que tokens revogados transitem.
2. **Falha de Broker RabbitMQ:** O `iam-core` utiliza a tabela transacional `security_outbox` no PostgreSQL. Falhas momentâneas do RabbitMQ não provocam perda de eventos; o publicador reexecuta com backoff exponencial assim que o broker for restabelecido.
3. **Falha do HashiCorp Vault:** Assinaturas criptográficas e concessões PAM exigem o Vault online. Diante de indisponibilidade do Vault, nenhuma operação privilegia chaves locais em memória; a emissão falha com segurança (*Fail-Closed*).

# ADR-071: API Gateway e Roteamento de Borda com Validação de Políticas e Revogação Redis

- **Status:** Aceito
- **Data:** 23/10/2026
- **Contexto:** Etapa 12A — Gateway e Demonstração Opcional
- **Decisores:** Engenharia SecureGate IAM

---

## 1. Contexto e Problema

Com a conclusão do núcleo v1.0.0, a arquitetura carecia de uma camada opcional de borda (*Edge / API Gateway*) capaz de unificar o roteamento externo, isolar a infraestrutura de backend e realizar filtragem de tráfego antes que requisições inválidas ou revogadas alcancem o `iam-core`.

Os requisitos essenciais incluíam:
1. Roteamento transparente de endpoints públicos de autenticação e metadados (`/.well-known/**`, `/oauth2/**`).
2. Interceptação precoce de rotas de recursos protegidos (`/api/resources/**`, `/api/pam/**`).
3. Consulta de projeções de revogação em tempo real mantidas no Redis pelo Policy Enforcer (`SecurityProjectionKeys`).
4. Comportamento rigorosamente seguro diante de falhas de dependência (*Fail-Closed* em indisponibilidade do Redis).
5. Desempenho não bloqueante de alta taxa de transferência (I/O reativo).

---

## 2. Decisão Arquitetural

Decidiu-se pela criação do módulo dedicado **`api-gateway`** baseado em **Spring Cloud Gateway (Spring WebFlux / Netty)**:

1. **Stack Tecnológica:**
   - Framework: Spring Cloud Gateway com Project Reactor (Netty não bloqueante).
   - Comunicação com Redis: `ReactiveStringRedisTemplate` com drivers assíncronos Lettuce.
   - Respostas de erro padronizadas: RFC 7807 (Problem Details).

2. **Filtro Global de Revogação de Borda (`TokenRevocationGatewayFilter`):**
   - Extrai e decodifica claims de JWTs de entrada (`jti`, `sid`, `sub`).
   - Mapeia chaves de projeção canônicas (`com.securegate.events.SecurityProjectionKeys`).
   - Se o token constar como revogado ou a conta estiver bloqueada no Redis, retorna imediatamente `HTTP 401 Unauthorized`.
   - Injeta o cabeçalho de auditoria `X-Gateway-Verified: true` para requisições liberadas.

3. **Política Estrita Fail-Closed:**
   - Em caso de timeout ou indisponibilidade de rede com o Redis, o Gateway recusa a requisição com `HTTP 503 Service Unavailable`.
   - O acesso a recursos protegidos nunca é autorizado sem verificação do estado de revogação.

4. **Empacotamento e Orquestração:**
   - Dockerfile *multi-stage* em Java 21 non-root (`securegate`, UID `10001`) expondo a porta `8082`.
   - Integração com `infra/docker-compose.yml` sob os perfis `gateway` e `full`.

---

## 3. Consequências

### Positivas:
- **Descarga de Backend:** Requisições com tokens revogados ou malformados são descartadas na borda, economizando ciclos de CPU, conexões JDBC e operações de banco de dados no `iam-core`.
- **Baixa Latência:** I/O reativo com Netty assegura baixíssimo overhead de roteamento (< 5 ms).
- **Consistência de Protocolo:** Compartilha exatamente as mesmas chaves de projeção Redis geradas pelo `policy-enforcer` (`SecurityProjectionKeys`).

### Neutras:
- Adição de um serviço satélite na orquestração Docker Compose, consumindo aproximadamente 128 MB a 256 MB adicionais de memória JVM.
- Requer conectividade de rede direta entre o Gateway e o cluster Redis.


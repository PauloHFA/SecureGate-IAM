# ADR-072: Jornada Demonstrável Ponta a Ponta, Coleções de API e Verificação de Borda

- **Status:** Aceito
- **Data:** 23/10/2026
- **Contexto:** Etapa 12B — Jornada demonstrável e coleções de API
- **Decisores:** Engenharia SecureGate IAM

---

## 1. Contexto e Problema

Com a criação do componente `api-gateway` na Etapa 12A, a arquitetura do SecureGate IAM estabeleceu o roteamento de borda e a checagem precoce de tokens revogados no Redis. Para consolidar a viabilidade operacional e a facilidade de homologação por times externos de segurança e auditoria, era necessário:

1. **Jornada Reproduzível Ponta a Ponta:** Um fluxo demonstrável claro e determinístico cobrindo o ciclo de vida completo de uma identidade e sessão:
   - Emissão de token OAuth2 (`POST /oauth2/token`).
   - Acesso com sucesso a recurso protegido via borda (`GET /api/resources/account` através da porta `8082` com `X-Gateway-Verified: true`).
   - Ocorrência de evento suspeito ou revogação administrativa (`POST /oauth2/revoke` ou bloqueio de conta).
   - Ingestão do evento assíncrono pelo Policy Enforcer e projeção imediata em Redis via chaves de wire protocol (`SecurityProjectionKeys`).
   - Recusa imediata na borda (`HTTP 401 Unauthorized` com detalhes RFC 7807) sem onerar o backend `iam-core`.
2. **Coleções de API Padronizadas e Versionáveis:** Disponibilizar coleções prontas para ferramentas modernas de API (Bruno e Postman v2.1) com variáveis de ambiente e asserções automatizadas.
3. **Scripts de Automação da Demonstração:** Scripts executáveis em PowerShell (`Test-DemonstrationJourney.ps1`) e Bash (`demonstrate-journey.sh`) para CI e demonstrações locais automatizadas.

---

## 2. Decisão Arquitetural

Decidiu-se pela formalização da **Jornada de Demonstração em 5 Etapas** e pela disponibilização das coleções padronizadas sob o diretório `docs/colecoes/`:

1. **Estrutura das Coleções de API (`docs/colecoes/`):**
   - **Bruno Collection (`docs/colecoes/bruno/`):** Formato declarativo em texto puro, versionável nativamente via Git sem ruído ou lock-in. Organizado nas pastas:
     - `01-health-checks/`: Verificação de saúde de Core e Gateway.
     - `02-oauth2-metadata/`: Descoberta OpenID Connect (`.well-known/openid-configuration` e `jwks.json`).
     - `03-identity-admin/`: Gestão de identidades via token de bootstrap.
     - `04-demonstration-journey/`: Execução sequencial dos passos 1 a 4 da jornada.
     - `05-pam/`: Fluxos de acesso temporário privilegiado (PAM).
   - **Postman Collection & Environment (`docs/colecoes/`):** Arquivos compatíveis com Postman Schema v2.1.0 (`securegate-iam.postman_collection.json` e `securegate-iam.postman_environment.json`) para interoperabilidade imediata com pipelines e ferramentas de QA.

2. **Jornada Demonstrável de Segurança em 5 Passos:**
   - **Passo 1 (Saúde & Prontidão):** Confirmação de que `iam-core`, `policy-enforcer`, `api-gateway` e Redis estão em estado `UP`.
   - **Passo 2 (Roteamento Público):** Verificação de que requisições públicas (`/.well-known/**`) transitam pelo Gateway sem exigir credenciais.
   - **Passo 3 (Acesso Autorizado):** Chamada a rota protegida (`/api/resources/account`) com Bearer token ativo; o Gateway valida ausência de revogação no Redis e encaminha com cabeçalho `X-Gateway-Verified: true`.
   - **Passo 4 (Revogação & Bloqueio em Borda):** Simulação de incidente de segurança que projeta a revogação no Redis sob `securegate:{revocation}:jti:...` ou bloqueio de conta. Nova chamada ao Gateway com o mesmo token resulta em bloqueio imediato na borda com `HTTP 401`.
   - **Passo 5 (Resiliência Fail-Closed):** Verificação de que a indisponibilidade forçada do Redis impede qualquer acesso indevido à área protegida via `HTTP 503`.

3. **Automação Executável:**
   - `infra/scripts/Test-DemonstrationJourney.ps1`: Script PowerShell com geração de JWT sintético, cálculo de hash SHA-256 e validação de asserções.
   - `infra/scripts/demonstrate-journey.sh`: Script Bash com formatação de tabela resumo e suporte a ambientes Linux/CI.
   - Teste de integração de governança `DemoJourneyIT.java` no módulo `integration-tests`.

---

## 3. Consequências

### Positivas:
- **Demonstração Determinística:** Qualquer desenvolvedor, auditor ou operador pode reproduzir o fluxo completo de segurança em minutos com dados sintéticos.
- **Interoperabilidade:** Suporte duplo a Bruno (prioritário por ser texto puro em Git) e Postman v2.1 (padrão de mercado para equipes corporativas).
- **Zero Vazamento de Credenciais:** As coleções utilizam placeholders e os scripts geram credenciais sintéticas e efêmeras.
- **Validação Contínua:** Os testes de integração garantem que coleções, scripts e filtros mantenham coerência com a evolução da arquitetura.

### Neutras:
- Os arquivos de coleção devem ser atualizados quando novos endpoints forem adicionados às rotas públicas ou protegidas.

# Evidências — Etapa 12B: Jornada Demonstrável Ponta a Ponta e Coleções de API

- **Data e responsável:** 23/10/2026, Engenharia SecureGate IAM
- **Branch:** `feat/demo-journey-api-collection`
- **Ambiente:** Windows, Java 21 OpenJDK, Maven 3.9+, GitHub Actions
- **ADR relacionada:** [ADR-072](../adr/072-jornada-demonstracao-e-colecao-de-apis.md)
- **Runbook operacional:** [Runbook da Jornada Demonstrável](../runbooks/jornada-demonstracao-local.md)

---

## 1. Escopo e Entregas da Etapa 12B

A entrega **12B** estabelece a reprodutibilidade prática da segurança ponta a ponta do ecossistema SecureGate IAM:

1. **Jornada de Demonstração em 5 Passos:**
   - **Passo 1 (Prontidão):** Health checks operacionais no IAM Core (`:8080`) e API Gateway (`:8082`).
   - **Passo 2 (Roteamento Público):** Acesso desimpedido e anônimo a metadados OpenID Connect (`/.well-known/openid-configuration`, `/.well-known/jwks.json`).
   - **Passo 3 (Acesso Autorizado):** Chamada a rota protegida (`/api/resources/account`) com token Bearer legítimo; validação de que o Gateway consulta Redis e anota a requisição com `X-Gateway-Verified: true`.
   - **Passo 4 (Incidente e Bloqueio em Borda):** Disparo de evento suspeito ou revogação administrativa; ingestão assíncrona pelo Policy Enforcer e projeção de chave canônica no Redis (`SecurityProjectionKeys`); recusa imediata em chamadas subsequentes com `HTTP 401 Unauthorized` e RFC 7807 Problem Details.
   - **Passo 5 (Resiliência Fail-Closed):** Comportamento seguro de recusa com `HTTP 503 Service Unavailable` diante de falha ou indisponibilidade simulada de Redis.

2. **Coleção de APIs Padronizada Bruno (`docs/colecoes/bruno/`):**
   - Coleção declarativa em texto puro, sem lock-in, pronta para commit Git.
   - Categorizada em 5 pastas: `01-health-checks`, `02-oauth2-metadata`, `03-identity-admin`, `04-demonstration-journey`, `05-pam`.
   - Ambiente declarativo `environments/Local.bru`.

3. **Coleção e Ambiente Postman (`docs/colecoes/`):**
   - Arquivo `securegate-iam.postman_collection.json` em conformidade com o Schema v2.1.0.
   - Arquivo de variáveis de ambiente `securegate-iam.postman_environment.json`.

4. **Scripts de Automação da Demonstração (`infra/scripts/`):**
   - `Test-DemonstrationJourney.ps1`: Script PowerShell com geração de JWT sintético e cálculo de chaves SHA-256.
   - `demonstrate-journey.sh`: Script Bash com formatação de tabela resumo e suporte a ambientes Linux/CI.

5. **Testes Automatizados de Contrato e Governança:**
   - Suíte de testes `DemoJourneyIT.java` no módulo `integration-tests`, validando integridade estrutural das coleções, variáveis de ambiente, sintaxe de scripts e chaves de projeção canônicas.

---

## 2. Validação Local

### Execução dos Testes Automatizados
```bash
./mvnw.cmd test -pl integration-tests -Dtest=DemoJourneyIT
```

Resultado observado:
- `validatesBrunoCollectionStructureAndRequests`: PASSED
- `validatesPostmanCollectionAndEnvironmentJson`: PASSED
- `validatesDemonstrationScripts`: PASSED
- `validatesCanonicalProjectionKeyFormatForRevocation`: PASSED

### Validação de Formatação e Código Limpo
```bash
./mvnw.cmd spotless:check
```
Resultado: 100% em conformidade com as regras do Google Java Format.

---

## 3. Próximos Passos

A entrega **12B** conclui a jornada demonstrável ponta a ponta e o conjunto oficial de coleções de API. A próxima e derradeira etapa do roadmap é a **12C (Materiais Finais)**, compreendendo a consolidação dos diagramas de arquitetura, gravação/registro da demonstração, fechamento da documentação e encerramento oficial do roadmap do SecureGate IAM.

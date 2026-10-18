# SecureGate IAM — Roteiro de implementação

Atualizado em 18/10/2026. Base: documento de arquitetura e plano v0.1, outubro de 2026.

Decisões aprovadas: Java 21, Spring Boot, Maven e Vault Transit desde a etapa de autenticação. Consulte [ADR-001](adr/001-stack-e-organizacao.md) e [ADR-002](adr/002-assinatura-jwt-vault-transit.md).

## Como acompanhar

1. Executar as etapas na ordem indicada e respeitar suas dependências.
2. Trabalhar em branches curtas; cada PR descreve objetivo, comportamento, validação e limitações.
3. Marcar tarefas somente depois de implementar e verificar o entregável.
4. Concluir uma etapa apenas após registrar PR/commit e evidências do aceite.
5. Registrar mudanças arquiteturais em ADRs e atualizar este roteiro.

Cada etapa pode exigir vários PRs. Os comandos abaixo são critérios futuros, não funcionalidades já disponíveis. A estimativa original de 12 a 14 semanas em tempo parcial é referência, não prazo acordado.

## Escopo

| Área | Entrega |
| --- | --- |
| Identity e Access | Usuários, credenciais, grupos, papéis, permissões e MFA TOTP |
| Authorization | Authorization Code + PKCE, Client Credentials, refresh com rotação, JWT e JWKS |
| Policy | RBAC + ABAC, risco determinístico, default deny e DENY prevalecendo |
| Eventos | RabbitMQ, Transactional Outbox e consumidores idempotentes |
| Estado distribuído | Redis para sessões, bloqueios, revogação e rate limiting |
| Dados | PostgreSQL, Flyway e auditoria particionada |
| Segredos | Vault KV, Transit, AppRole e credenciais dinâmicas |
| Qualidade e operação | Testcontainers, ArchUnit, JMeter, observabilidade, Compose e GitHub Actions |

Gateway é opcional na etapa 12. Federação externa, WebAuthn e painel administrativo completo ficam fora da v1. Não implementar Implicit ou Resource Owner Password.

## Estrutura planejada

```text
SecureGate-IAM/
├── pom.xml
├── mvnw / mvnw.cmd / .mvn/
├── iam-core/
├── policy-enforcer/
├── libs/security-events/
├── gateway/                     # etapa opcional
├── infra/
│   ├── docker-compose.yml
│   ├── postgres/
│   ├── rabbitmq/
│   ├── vault/
│   └── observability/
├── load-tests/jmeter/
├── docs/
│   ├── adr/
│   ├── evidencias/
│   └── runbooks/
└── .github/workflows/
```

Não adicionar o gateway ao build obrigatório antes de implementá-lo. A árvore representa a organização futura.

No `iam-core`, usar `com.securegate.iam.<contexto>`, com contextos `identity`, `access`, `policy`, `authorization`, `audit` e `session`. Cada um contém `domain`, `application`, `infrastructure` e `presentation`.

## Situação das etapas

| Etapa | Entrega | Dependências | Estado |
| --- | --- | --- | --- |
| 0 | Fundação e build Maven | Decisões aprovadas | Concluída; ver evidências |
| 1 | Infraestrutura local | 0 | Concluída localmente; ver evidências |
| 2 | Esqueleto e CI | 0–1 | Pendente |
| 3 | Identity e Access | 2 | Pendente |
| 4 | OAuth/OIDC e Transit | 3 e Vault da etapa 1 | Pendente |
| 5 | Políticas, risco e MFA | 3–4 | Pendente |
| 6 | Eventos e Outbox | 2–5 | Pendente |
| 7 | Enforcer e revogação | 4–6 | Pendente |
| 8 | Auditoria e desempenho | 6–7 | Pendente |
| 9 | Vault avançado e PAM | 4–8 | Pendente |
| 10 | Carga | 5–9 | Pendente |
| 11 | Entrega e hardening | 0–10 | Pendente |
| 12 | Gateway e demonstração | 11 | Opcional, pendente |

## Etapa 0 — Fundação do repositório

**Objetivo:** estabelecer organização, versões e um build reproduzível.

- [x] Criar repositório e README inicial.
- [x] Ignorar o cache `.firecrawl/`.
- [x] Registrar decisões de stack e assinatura nos ADRs.
- [x] Verificar compatibilidade em fontes oficiais e registrar versões e limites da verificação no ADR-001.
- [x] Criar POM agregador e módulos `iam-core`, `policy-enforcer` e `libs/security-events`.
- [x] Adicionar Maven Wrapper, fixar Java 21 e centralizar versões de plugins e dependências.
- [x] Configurar formatação e ampliar `.gitignore` para Maven, IDEs e segredos locais.
- [x] Adicionar `CODEOWNERS` com o proprietário do repositório, `@PauloHFA`.
- [x] Criar teste de comportamento real: validação dos tipos de evento usados no contrato de segurança.

**Aceite:** clone limpo executa `./mvnw verify` ou `.\mvnw.cmd verify` no Windows. Registrar versões, comando e resultado em `docs/evidencias/etapa-00.md`.

Subdivisão executada:

- **0A — Build:** POM agregador, BOMs compatíveis e três módulos.
- **0B — Ferramentas:** Wrapper, Enforcer, Spotless, regras de arquivos e CODEOWNERS.
- **0C — Validação:** testes de `SecurityEventType`, build limpo e [registro de evidências](evidencias/etapa-00.md).

Os módulos de serviço nesta etapa são bibliotecas compiláveis, sem classe principal. O bootstrap do `iam-core` fica na etapa 2; o consumidor, na etapa 7. O contrato de tipos é uma base mínima para a etapa 6, sem broker ou envelope implementado.

## Etapa 1 — Infraestrutura local

- [x] Criar `infra/docker-compose.yml` com PostgreSQL, Redis, RabbitMQ e Vault.
- [x] Fixar versões, configurar volumes, healthchecks e portas locais em loopback.
- [x] Manter credenciais fora do versionamento e disponibilizar `.env.example` com instruções.
- [x] Criar exchange `security.events`, filas de enforcer e auditoria, bindings, DLX e retry com TTL.
- [x] Inicializar Vault KV v2, Transit, chave RSA e AppRoles com políticas mínimas.
- [x] Tornar inicialização repetível e comprovar preservação da chave e credenciais válidas.
- [x] Documentar subida, parada, diagnóstico e limitações do Vault dev.

**Aceite:** após `Initialize-Local.ps1`, `docker compose --env-file .env -f infra/docker-compose.yml up -d --wait --wait-timeout 180` sobe dependências saudáveis; fila recebe mensagem e Transit assina conteúdo de teste. Resultados em [evidências da etapa 1](evidencias/etapa-01.md); operação em [infra/README.md](../infra/README.md).

Subdivisão executada: **1A**, Compose/credenciais; **1B**, topologia RabbitMQ; **1C**, bootstrap Vault; **1D**, smoke test e repetição do bootstrap. A porta PostgreSQL do host é `15432` devido ao bloqueio local de `5432`; a porta interna permanece `5432`. RabbitMQ 4.3.6 substitui a referência 3.13 conforme [ADR-003](adr/003-infraestrutura-local.md).

## Etapa 2 — Esqueleto do iam-core e CI

- [ ] Criar aplicação Spring Boot e pacotes dos seis contextos.
- [ ] Configurar Actuator e probes com exposição mínima de endpoints.
- [ ] Configurar PostgreSQL e primeira migração Flyway.
- [ ] Padronizar erros com Problem Details e correlação sem dados sensíveis.
- [ ] Criar regras ArchUnit para camadas e fronteiras entre contextos.
- [ ] Adicionar Testcontainers com PostgreSQL real e execução das migrações.
- [ ] Configurar Maven para executar testes unitários e integração em `verify`.
- [ ] Criar CI com JDK 21, build, formatação, testes e relatórios.

**Aceite:** CI verde; contexto sobe com banco real; regras detectam uma violação arquitetural conhecida. Registrar relatórios e comandos.

## Etapa 3 — Identity e Access

- [ ] Modelar usuários, ciclo de vida, credenciais separadas, papéis, permissões e grupos.
- [ ] Implementar hash Argon2id, validações e proteção contra exposição de senha.
- [ ] Criar migrações, repositórios e casos de uso de provisionamento/atribuição.
- [ ] Expor API administrativa protegida por bootstrap temporário documentado, substituível por OAuth.
- [ ] Impedir administração anônima desde a primeira entrega.
- [ ] Registrar auditoria básica, preparando migração para etapa 8.
- [ ] Testar autorização, conflitos, entradas inválidas, concorrência relevante e ausência de enumeração.
- [ ] Configurar JaCoCo com cobertura mínima de 80% para domínio/aplicação destes contextos.

**Aceite:** provisionar usuário, associar grupo/papel e verificar permissões em testes; administração anônima é rejeitada; cobertura mínima satisfeita.

## Etapa 4 — Authorization Server e Transit

- [ ] Integrar Spring Authorization Server e persistir clientes, autorizações e consentimentos.
- [ ] Iniciar com JDBC padrão se reduzir risco; justificar e testar customização JPA.
- [ ] Implementar Authorization Code + PKCE, Client Credentials e refresh com rotação/detecção de reuso.
- [ ] Validar `redirect_uri` por correspondência exata e proteger autorização/consentimento.
- [ ] Implementar porta e adaptador Transit conforme ADR-002.
- [ ] Emitir JWT RS256 com `kid`, `roles`, `sid` e claims aplicáveis a cada fluxo.
- [ ] Publicar discovery, authorize, token, JWKS, revogação, introspecção e userinfo.
- [ ] Implementar rotação e sobreposição das chaves públicas.
- [ ] Testar expiração, emissor/audiência, assinatura incorreta, token adulterado, `alg=none` e reuso de refresh.
- [ ] Testar Vault indisponível sem fallback local.

**Aceite:** clientes completam os fluxos; biblioteca independente verifica JWT via JWKS; rotação preserva validação dos tokens anteriores na janela definida; testes negativos passam.

## Etapa 5 — Políticas, risco e MFA

- [ ] Persistir políticas JSONB com prioridade, versão e estado habilitado.
- [ ] Implementar default deny e precedência de DENY explícito.
- [ ] Definir combinação dos demais efeitos em tabela de decisão.
- [ ] Retornar ALLOW, ALLOW_WITH_MFA, ALLOW_LIMITED ou DENY com motivos e políticas aplicadas.
- [ ] Implementar RBAC e DSL JSON restrita para ABAC, sem execução arbitrária de código.
- [ ] Coletar sinais confiáveis de identidade, dispositivo, rede, comportamento e recurso.
- [ ] Calcular risco determinístico de 0 a 100 e tratar sinais ausentes/inválidos.
- [ ] Implementar TOTP, códigos de recuperação com hash e step-up.
- [ ] Aplicar decisões no login, emissão de token e autorização de recursos.

**Aceite:** testes parametrizados cobrem tabela de decisão, conflitos, ausência de política, MFA incompleto e redução de escopo/TTL.

## Etapa 6 — Eventos e Outbox

- [ ] Criar envelope versionado e testes de contrato em `security-events`.
- [ ] Incluir identificador, tipo, versão, horário, correlação, ator, contexto e dados necessários.
- [ ] Cobrir `auth.login.suspicious`, `auth.login.failed`, `token.revoked`, `session.terminated`, `policy.changed` e `user.locked`.
- [ ] Gravar Outbox na mesma transação da mudança de estado.
- [ ] Publicar com confirmação, retry e tratamento de mensagens não roteadas.
- [ ] Validar topologia RabbitMQ, TTL e DLQ.
- [ ] Definir limpeza, retenção e métricas da Outbox.

**Aceite:** rollback não publica mudança não confirmada; queda do broker não perde evento persistido; recuperação publica pendências; produtor e consumidores respeitam o contrato.

## Etapa 7 — Enforcer, Redis e revogação

- [ ] Implementar consumidores com ack manual e idempotência por `eventId`.
- [ ] Não marcar evento como concluído antes de aplicar seu efeito.
- [ ] Atualizar sessões, bloqueios por usuário/IP e revogação por `jti`/`sid`.
- [ ] Calcular TTL conforme expiração do token, sessão ou bloqueio.
- [ ] Tratar duplicação e eventos fora de ordem com regras de versão/timestamp.
- [ ] Consultar bloqueio/revogação no `iam-core` e definir comportamento com Redis indisponível.
- [ ] Implementar rate limiting por conta/IP e logout/revogação ponta a ponta.
- [ ] Medir propagação da confirmação da revogação até a rejeição no recurso protegido.

**Aceite:** token anteriormente aceito passa a ser rejeitado em menos de 2 segundos no ambiente medido; integração cobre duplicação, reordenação e falhas temporárias.

## Etapa 8 — Auditoria e otimização

- [ ] Criar auditoria particionada por mês em `occurred_at` com Flyway.
- [ ] Usar chave `(id, occurred_at)` e criar partições futuras automaticamente.
- [ ] Garantir deduplicação global de evento apesar das restrições de unicidade de tabelas particionadas; documentar a solução.
- [ ] Restringir identidade de ingestão a INSERT/SELECT e separar permissões operacionais.
- [ ] Implementar consumidor com lote e recuperação de falhas.
- [ ] Criar consulta por cursor estável, filtros e índices.
- [ ] Definir retenção/arquivamento preservando imutabilidade.
- [ ] Medir N+1, planos SQL e ingestão com milhões de registros.

**Aceite:** duplicação não cria múltiplos registros; aplicação não altera/exclui auditoria; cursor lida com timestamps iguais; planos e medições documentados.

## Etapa 9 — Vault avançado e PAM

- [ ] Integrar KV e credenciais temporárias de banco.
- [ ] Renovar tokens, credenciais e leases, recuperando falhas de renovação.
- [ ] Testar rotação de credenciais com pool de conexões.
- [ ] Criar endpoint de acesso privilegiado com autorização, justificativa e TTL limitado.
- [ ] Auditar emissão, expiração e revogação sem registrar o segredo.
- [ ] Documentar entrega da credencial inicial de autenticação do serviço no Vault.
- [ ] Criar runbook de indisponibilidade e recuperação.

**Aceite:** leases expiram/renovam corretamente; pedido não autorizado falha; segredos não ficam em arquivos versionados, imagens ou logs.

## Etapa 10 — Carga e observabilidade

- [ ] Criar planos JMeter e massa reproduzível de usuários/clientes.
- [ ] Cobrir Client Credentials, login PKCE, validação/revogação, falhas de login e auditoria.
- [ ] Instrumentar métricas, logs estruturados e tracing propagado por HTTP/RabbitMQ.
- [ ] Subir Prometheus/Grafana e correlacionar latência, filas, banco, Redis e Vault.
- [ ] Executar ramp-up, patamar e ramp-down, registrando recursos do ambiente.
- [ ] Medir taxa efetiva: 300 usuários virtuais não equivalem a 300 requisições/s.
- [ ] Gerar relatórios HTML e documentar gargalos/ajustes.

**Aceite:** metas abaixo medidas, sem crescimento contínuo de memória ou acúmulo sustentado de filas. Desvios exigem evidência e plano de correção, sem declarar cumprimento não medido.

## Etapa 11 — Entrega e hardening

- [ ] Criar imagens multi-stage Java 21 com usuário sem privilégios e versões fixadas.
- [ ] Adicionar perfil Compose `full`, probes e dependências saudáveis.
- [ ] Criar smoke test da stack com saúde, discovery e JWKS.
- [ ] Completar CI com dependências, CodeQL, cobertura e scan de imagens.
- [ ] Publicar imagens versionadas no GHCR no fluxo de release, sem publicação de PR.
- [ ] Documentar TLS, CORS, cabeçalhos, limites, mascaramento de logs e segredos.
- [ ] Produzir threat model e revisão de controles baseada no OWASP ASVS.
- [ ] Configurar proteção de `main`, revisão obrigatória e checks exigidos.
- [ ] Publicar runbooks e release v1.0.0 após os critérios finais.

**Aceite:** `docker compose -f infra/docker-compose.yml --profile full up -d` sobe a stack; CI/CD passa; imagens e instruções funcionam em ambiente limpo.

## Etapa 12 — Gateway e demonstração opcional

- [ ] Implementar gateway com JWT, revogação Redis e políticas por rota.
- [ ] Definir comportamento quando dependências falham.
- [ ] Demonstrar login → acesso → evento suspeito → bloqueio → acesso negado.
- [ ] Completar diagramas, ADRs, coleção Bruno/Postman e gravação da demonstração.

**Aceite:** fluxo completo reproduzível e testado. Esta etapa não bloqueia a v1 do núcleo.

## Metas de referência

| Indicador | Meta |
| --- | --- |
| `/oauth2/token` | p95 < 200 ms a 300 requisições/s |
| Introspecção/validação com cache | p95 < 20 ms |
| Propagação de revogação | < 2 s |
| Erros em cenários esperados como sucesso | < 0,5% |
| Cobertura de domínio e aplicação | ≥ 80% |
| Serviços stateless | Escala horizontal sem depender de estado local |

Separar rejeições esperadas dos testes negativos dos erros operacionais ao medir a taxa de erro. Registrar ambiente, duração e método.

## Evidências e definição de pronto

Criar `docs/evidencias/etapa-NN.md` ao validar cada etapa:

```markdown
# Evidências — Etapa NN
- Data e responsável:
- PR/commit:
- Ambiente e versões:
- Comandos executados:
- Resultado observado:
- Relatórios/artefatos:
- Critério de aceite atendido:
- Pendências e limitações:
```

Usar identidades sintéticas nos testes e não registrar senhas, tokens ou chaves privadas. Mudanças funcionais exigem testes proporcionais ao risco; documentação exige revisão de coerência e links. O desenvolvimento passa a seguir a revisão obrigatória prevista no documento original.

Para encerrar a v1:

- [ ] Stack completa inicia com o Compose documentado.
- [ ] OAuth/OIDC e refresh com rotação funcionam; JWT Transit é verificável via JWKS.
- [ ] Políticas/MFA aplicam os quatro resultados com motivos auditáveis.
- [ ] Outbox e consumidores toleram falhas e duplicação.
- [ ] Revogação atende à meta medida nos recursos protegidos.
- [ ] Auditoria é particionada, consultável por cursor e imutável para a aplicação.
- [ ] Segredos e credenciais temporárias são gerenciados pelo Vault.
- [ ] CI, segurança, arquitetura e cobertura passam.
- [ ] JMeter registra resultados e eventuais desvios.
- [ ] README, ADRs e runbooks permitem executar e operar o projeto.

## Próxima entrega

Executar a etapa 2: bootstrap do `iam-core`, Actuator, Flyway, isolamento de camadas/contextos com ArchUnit, PostgreSQL via Testcontainers e CI inicial. As etapas 0 e 1 estão concluídas localmente conforme as evidências.

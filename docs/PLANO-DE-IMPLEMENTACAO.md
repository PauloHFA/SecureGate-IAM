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
| 2 | Esqueleto e CI | 0–1 | Concluída; validação local e CI GitHub aprovadas |
| 3 | Identity e Access | 2 | Incluída na base publicada 5A, com CI aprovada; revisão/merge pendentes |
| 4 | OAuth/OIDC e Transit | 3 e Vault da etapa 1 | 4A–4D2 publicadas na base 5A, com CI aprovada; revisão/merge pendentes |
| 5 | Políticas, risco e MFA | 3–4 | Em andamento; 5A publicada, 5B/5C locais; aplicação das decisões na 5D pendente |
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

- [x] Criar aplicação Spring Boot e pacotes dos seis contextos.
- [x] Configurar Actuator e probes com exposição mínima de endpoints.
- [x] Configurar PostgreSQL e primeira migração Flyway.
- [x] Padronizar erros com Problem Details e correlação sem dados sensíveis.
- [x] Criar regras ArchUnit para camadas e fronteiras entre contextos.
- [x] Adicionar Testcontainers com PostgreSQL real e execução das migrações.
- [x] Configurar Maven para executar testes unitários e integração em `verify`.
- [x] Criar CI com JDK 21, build, formatação, testes e relatórios.
- [x] Publicar a branch em PR e confirmar a execução verde da CI no GitHub.

**Aceite:** CI verde; contexto sobe com banco real; regras detectam uma violação arquitetural conhecida. Registrar relatórios e comandos.

Subdivisão executada: **2A**, bootstrap/segurança/probes; **2B**, PostgreSQL/Flyway; **2C**, arquitetura e testes HTTP; **2D**, workflow CI. As verificações locais e a [CI no GitHub](https://github.com/PauloHFA/SecureGate-IAM/actions/runs/39137410438) passaram com 40 testes. O [PR #4](https://github.com/PauloHFA/SecureGate-IAM/pull/4) fica disponível para revisão; concluir o aceite da etapa não implica merge automático. Consultar [evidências](evidencias/etapa-02.md) e [runbook](runbooks/iam-core-local.md).

## Etapa 3 — Identity e Access

- [x] Modelar usuários, ciclo de vida, credenciais separadas, papéis, permissões e grupos.
- [x] Implementar hash Argon2id, validações e proteção contra exposição de senha.
- [x] Criar migrações, repositórios e casos de uso de provisionamento/atribuição.
- [x] Expor API administrativa protegida por bootstrap temporário documentado, substituível por OAuth.
- [x] Impedir administração anônima desde a primeira entrega.
- [x] Registrar auditoria básica, preparando migração para etapa 8.
- [x] Testar autorização, conflitos, entradas inválidas, concorrência relevante e ausência de enumeração pela API administrativa.
- [x] Configurar JaCoCo com cobertura mínima de 80% por pacote de domínio/aplicação destes contextos.

**Aceite:** provisionar usuário, associar grupo/papel e verificar permissões em testes; administração anônima é rejeitada; cobertura mínima satisfeita.

Subdivisão executada: **3A**, usuários/credenciais/ciclo de vida; **3B**, catálogos e atribuições RBAC; **3C**, bootstrap administrativo/auditoria; **3D**, testes e cobertura. As alterações estão na branch local `feat/identity-access`, baseada na etapa 2. Consultar [evidências](evidencias/etapa-03.md), [decisões](adr/004-identity-access-bootstrap.md) e [guia de provisionamento](runbooks/provisionamento-local.md). Publicação e execução remota da CI desta branch ainda não foram realizadas.

## Etapa 4 — Authorization Server e Transit

- [x] Integrar Spring Authorization Server e persistir clientes, autorizações e consentimentos.
- [x] Iniciar com JDBC padrão se reduzir risco; justificar e testar customização JPA.
- [x] Implementar Authorization Code + PKCE, Client Credentials e refresh com rotação/detecção de reuso.
- [x] Validar `redirect_uri` por correspondência exata e proteger autorização/consentimento.
- [x] Implementar porta e adaptador Transit conforme ADR-002.
- [x] Emitir access tokens JWT RS256 com `kid` e claims aplicáveis; fluxo de usuário inclui `roles` e `sid`.
- [x] Disponibilizar discovery, authorize, token, JWKS, revogação, introspecção e userinfo na aplicação; publicação da branch ainda pendente.
- [x] Implementar rotação e sobreposição das chaves públicas, com retenção de todas as versões e teste de troca no Vault.
- [x] Testar expiração, emissor/audiência, assinatura incorreta, token adulterado, `alg=none` e reuso de refresh nos fluxos OAuth implementados.
- [x] Testar Vault indisponível sem fallback local.

**Aceite:** clientes completam os fluxos; biblioteca independente verifica JWT via JWKS; rotação preserva validação dos tokens anteriores na janela definida; testes negativos passam.

Subdivisão da etapa:

- **4A — Transit e JWKS:** porta de assinatura, AppRole por arquivos, encoder RS256, endpoint público condicionado à ativação, cache, métricas e testes com Vault real. Ver [ADR-005](adr/005-transit-e-jwks.md), [runbook](runbooks/transit-local.md) e [evidências](evidencias/etapa-04.md).
- **4B — Authorization Server e Client Credentials:** framework integrado, clientes/autorizações/consentimentos JDBC, cadastro administrativo protegido, segredo gerado com hash Argon2id, issuer/audiência e TTL de cinco minutos definidos. Ver [ADR-006](adr/006-authorization-server-client-credentials.md), [runbook](runbooks/oauth-client-credentials-local.md) e [evidências](evidencias/etapa-04b.md).
- **4C — Login, Authorization Code e PKCE:** login Identity/Argon2id, clientes web confidenciais, redirects exatos, S256 obrigatório, consentimento protegido por CSRF e sessão, código com uso único transacional e JWT com papéis/sid. Ver [ADR-007](adr/007-login-pkce-e-consentimento.md), [runbook](runbooks/oauth-pkce-local.md) e [evidências](evidencias/etapa-04c.md).
- **4D1 — Ciclo dos tokens:** refresh opcional com rotação, prazo absoluto de 30 minutos, histórico de hashes, detecção de reuso transacional, introspecção e revogação pelo cliente proprietário. Implementada e validada localmente com 190 testes. Ver [ADR-008](adr/008-refresh-rotacao-e-revogacao.md), [runbook](runbooks/oauth-refresh-local.md) e [evidências](evidencias/etapa-04d1.md).
- **4D2 — OIDC:** discovery OAuth/OIDC, ID token Transit com audiência do cliente, nonce obrigatório, auth_time/sid e userinfo condicionado a escopos/estado atual da identidade. Cadastro web opta por `oidc: true`, adicionando `openid` automaticamente; `offline_access` continua indisponível. Ver [ADR-009](adr/009-oidc-discovery-e-userinfo.md), [runbook](runbooks/oidc-local.md) e [evidências](evidencias/etapa-04d2.md).

As entregas 4A–4D2 cobrem o escopo funcional da etapa 4 e estão incluídas na base publicada até a 5A, com CI aprovada; revisão e merge continuam pendentes. Client Credentials e Authorization Code com PKCE emitem tokens RS256; clientes web podem optar por refresh rotativo e OIDC. Consentimento interativo está implementado. Access tokens de usuário recebem papéis recalculados na renovação e sid; ID tokens têm audiência do cliente e não recebem papéis de API. Client Credentials não recebe claims de usuário. Nome/e-mail exigem escopos aprovados, sem afirmar email_verified. Remoção de versões antigas exige política de retenção considerando o maior TTL de todos os fluxos futuros, além dos cinco minutos atuais. Sessão de navegador ainda é local à instância. Introspecção e userinfo já refletem revogação no servidor; propagação para recursos com verificação offline permanece na etapa 7. A implementação não declara certificação OpenID nem suporte a clientes públicos, registro dinâmico ou logout OIDC.

## Etapa 5 — Políticas, risco e MFA

- [x] Persistir políticas JSONB com prioridade, versão e estado habilitado.
- [x] Implementar default deny e precedência de DENY explícito no motor de avaliação.
- [x] Definir combinação dos demais efeitos em tabela de decisão.
- [x] Retornar ALLOW, ALLOW_WITH_MFA, ALLOW_LIMITED ou DENY com motivos e IDs/versões das políticas aplicadas na avaliação administrativa.
- [x] Implementar RBAC e DSL JSON restrita para ABAC, sem execução arbitrária de código, no motor administrativo.
- [x] Coletar sinais de identidade, navegador lembrado/aprovado, conexão observada, falhas reais e perfil administrado de recurso no modelo local documentado.
- [x] Calcular risco determinístico de 0 a 100 e tratar sinais ausentes/inválidos, com coleta real local na 5B2.
- [x] Implementar TOTP, códigos de recuperação com hash e prova de step-up vinculada à sessão.
- [ ] Aplicar decisões no login, emissão de token e autorização de recursos.

**Aceite:** testes parametrizados cobrem tabela de decisão, conflitos, ausência de política, MFA incompleto e redução de escopo/TTL.

Subdivisão da etapa:

- **5A — Fundação de políticas:** JSONB com gramática fechada v1, target exato/`*`, condição por papéis atuais, prioridade, estado, versão otimista, auditoria transacional, tabela de decisão e simulação administrativa. DENY prevalece; limites são intersectados, com menor TTL; MFA permanece um requisito pendente. Ver [ADR-010](adr/010-politicas-e-tabela-de-decisao.md), [runbook](runbooks/politicas-local.md) e [evidências](evidencias/etapa-05a.md).
- **5B1 — Motor ABAC/risco e cenários:** gramática v2 com condições tipadas ALL, score determinístico de 0–100, tratamento conservador de ausência/expiração e endpoint de cenários administrativos identificado como simulação. V1 preservada e V6 amplia a restrição JSONB sem reescrever dados. Implementada e validada localmente com 311 testes. Ver [ADR-011](adr/011-abac-e-risco-deterministico.md), [runbook](runbooks/abac-risco-local.md) e [evidências](evidencias/etapa-05b1.md).
- **5B2 — Coleta confiável:** produtor interno do navegador autenticado, contexto vinculado a identidade/versão/sid, cookie emitido no servidor com aprovação administrativa expiráveis, classificação CIDR da conexão, contador de falhas com janela/aquecimento e sensibilidade de recurso versionada. Logout/reautenticação fecham contexto anterior; IDs não renovam observações. V7 adiciona persistência; origem encaminhada não é confiada. Ver [ADR-012](adr/012-coleta-confiavel-de-risco.md), [runbook](runbooks/coleta-risco-local.md) e [evidências](evidencias/etapa-05b2.md).
- **5C1 — Base TOTP e cifra:** sementes de 160 bits, TOTP SHA1/6 dígitos/30 segundos, contador de correspondência e cifra AES-256-GCM pelo Vault com vínculo ao usuário/inscrição. Ver [ADR-013](adr/013-base-totp-e-cifra-transit.md), [guia](runbooks/mfa-base-local.md) e [evidências](evidencias/etapa-05c1.md).
- **5C2 — Cadastro, recuperação e step-up:** formulários autenticados/CSRF, senha e login recentes, V8 com fator cifrado/recuperação em hash, consumo transacional por conta, bloqueio de tentativas, auditoria e prova de cinco minutos vinculada à sessão e à versão da identidade. Logout/reautenticação encerram o sid sem reabertura por requisições em andamento. Ver [ADR-014](adr/014-cadastro-mfa-recuperacao-e-step-up.md), [guia](runbooks/mfa-local.md) e [evidências](evidencias/etapa-05c2.md).
- **5D — Aplicação das decisões:** integrar políticas/risco/MFA ao login, emissão e recursos, respeitando escopos consentidos e limites de TTL. Pendente.

A 5C2 não encerra a etapa 5: avaliações são recomendações administrativas e ainda não alteram grants/tokens. Papéis vêm do banco; cenários sintéticos nunca comprovam contexto ou MFA. A prova real da 5C2 está disponível pela porta `MfaProofs`, mas sua aplicação ao efeito ALLOW_WITH_MFA depende da 5D. Cadastro MFA continua opcional; remoção/substituição de fator ativo e regeneração de códigos permanecem fora do escopo atual. A confiança do dispositivo representa navegador lembrado, sem atestação de hardware; a rede representa o par conectado ao IAM, sem confiança em forwarded headers. Cobertura do contador de falhas de login é local ao processo, com aquecimento de cinco minutos; tentativas MFA são persistidas por conta. Operação multi-instância do navegador e retenção/expurgo precisam das entregas operacionais. O aceite de aplicação efetiva de MFA/escopos/TTL será completado na 5D. A base até a 5A foi publicada e passou na [CI](https://github.com/PauloHFA/SecureGate-IAM/actions/runs/39159901831), com 243 testes; entregas posteriores ainda aguardam publicação/CI remota. Revisão/merge continuam pendentes.

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

Seguir para a 5D: aplicar políticas, risco e comprovação MFA ao login, emissão e recursos, preservando consentimento, escopos e limites de TTL. A 5C2 está na branch `feat/mfa-enrollment-step-up`; a 5C1 está no commit local `90282bb`. A 5A (`8f5666d`) foi publicada em `feat/policy-foundation` e passou na CI. A 5B1 está no commit local `738c660`; a 5B2 está no commit local `3e6b136`; push e CI remota dessas entregas pendentes. Revisão e merge continuam pendentes.

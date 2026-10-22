# SecureGate IAM — Roteiro de implementação

Atualizado em 20/10/2026. Base: documento de arquitetura e plano v0.1, outubro de 2026.

Decisões aprovadas: Java 21, Spring Boot, Maven e Vault Transit desde a etapa de autenticação. Consulte [ADR-001](adr/001-stack-e-organizacao.md) e [ADR-002](adr/002-assinatura-jwt-vault-transit.md).

## Como acompanhar

O [guia de implementação passo a passo](GUIA-DE-IMPLEMENTACAO.md) consolida todas as subdivisões executadas, suas sequências, links de operação/evidências e propostas para 9C/etapas futuras. O [índice da documentação](README.md) organiza os documentos. Atualizar esses registros a cada entrega e nova divisão, preservando a distinção entre trabalho proposto, implementado, testado e publicado.

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
| 5 | Políticas, risco e MFA | 3–4 | Implementada e publicada até 5D3, CI aprovada; revisão e merge pendentes |
| 6 | Eventos e Outbox | 2–5 | 6A–6C publicadas; CI/revisão pendentes |
| 7 | Enforcer e revogação | 4–6 | Escopo funcional publicado na base 8B; CI/revisão pendentes |
| 8 | Auditoria e desempenho | 6–7 | Escopo local até 8D2 concluído; publicação 8C/8D, CI/revisão pendentes |
| 9 | Vault avançado e PAM | 4–8 | Em andamento; 9A/9B locais, auditoria operacional e aceite pendentes |
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
- [x] Aplicar decisões na navegação de autenticação/autorização, emissão/refresh e recurso local por operação (5D1–5D3). A senha isolada não recebe bloqueio global por política; propagação para recursos externos permanece na etapa 7.

**Aceite:** testes parametrizados cobrem tabela de decisão, conflitos, ausência de política, MFA incompleto e redução de escopo/TTL.

Subdivisão da etapa:

- **5A — Fundação de políticas:** JSONB com gramática fechada v1, target exato/`*`, condição por papéis atuais, prioridade, estado, versão otimista, auditoria transacional, tabela de decisão e simulação administrativa. DENY prevalece; limites são intersectados, com menor TTL; MFA permanece um requisito pendente. Ver [ADR-010](adr/010-politicas-e-tabela-de-decisao.md), [runbook](runbooks/politicas-local.md) e [evidências](evidencias/etapa-05a.md).
- **5B1 — Motor ABAC/risco e cenários:** gramática v2 com condições tipadas ALL, score determinístico de 0–100, tratamento conservador de ausência/expiração e endpoint de cenários administrativos identificado como simulação. V1 preservada e V6 amplia a restrição JSONB sem reescrever dados. Implementada e validada localmente com 311 testes. Ver [ADR-011](adr/011-abac-e-risco-deterministico.md), [runbook](runbooks/abac-risco-local.md) e [evidências](evidencias/etapa-05b1.md).
- **5B2 — Coleta confiável:** produtor interno do navegador autenticado, contexto vinculado a identidade/versão/sid, cookie emitido no servidor com aprovação administrativa expiráveis, classificação CIDR da conexão, contador de falhas com janela/aquecimento e sensibilidade de recurso versionada. Logout/reautenticação fecham contexto anterior; IDs não renovam observações. V7 adiciona persistência; origem encaminhada não é confiada. Ver [ADR-012](adr/012-coleta-confiavel-de-risco.md), [runbook](runbooks/coleta-risco-local.md) e [evidências](evidencias/etapa-05b2.md).
- **5C1 — Base TOTP e cifra:** sementes de 160 bits, TOTP SHA1/6 dígitos/30 segundos, contador de correspondência e cifra AES-256-GCM pelo Vault com vínculo ao usuário/inscrição. Ver [ADR-013](adr/013-base-totp-e-cifra-transit.md), [guia](runbooks/mfa-base-local.md) e [evidências](evidencias/etapa-05c1.md).
- **5C2 — Cadastro, recuperação e step-up:** formulários autenticados/CSRF, senha e login recentes, V8 com fator cifrado/recuperação em hash, consumo transacional por conta, bloqueio de tentativas, auditoria e prova de cinco minutos vinculada à sessão e à versão da identidade. Logout/reautenticação encerram o sid sem reabertura por requisições em andamento. Ver [ADR-014](adr/014-cadastro-mfa-recuperacao-e-step-up.md), [guia](runbooks/mfa-local.md) e [evidências](evidencias/etapa-05c2.md).
- **5D1 — Emissão e renovação:** default deny obrigatório em Authorization Code/refresh de usuário, alvo `oauth:<client_id>` / `issue`, avaliação real de papéis/risco, prova MFA do sid original, redução coerente de escopo/TTL em JWT/resposta/JDBC, claims OIDC filtradas e auditoria transacional. Ver [ADR-015](adr/015-politicas-na-emissao-de-tokens.md), [guia](runbooks/politicas-tokens-local.md) e [evidências](evidencias/etapa-05d1.md).
- **5D2 — Navegação e desafio:** orientação real antes de consentimento e nova checagem ao aprová-lo, desafio local, retorno de cinco minutos vinculado a usuário/versão/sid, uso único por POST/CSRF, parâmetros normalizados e revalidação. Ativação preserva exibição de recuperação; prompt=none não inicia interação. Ver [ADR-016](adr/016-desafio-mfa-na-autorizacao.md), [guia](runbooks/desafio-mfa-local.md) e [evidências](evidencias/etapa-05d2.md).
- **5D3 — Recursos:** API Bearer stateless `GET /api/resources/account`, alvo/ação/escopo fixos, JWT e grant atual, identidade/versão, política real por chamada, prova do sid original, interseção e TTL contado desde iat, auditoria antes da resposta e contrato interno com o enforcer. Ver [ADR-017](adr/017-autorizacao-por-operacao-nos-recursos.md), [guia](runbooks/recursos-local.md) e [evidências](evidencias/etapa-05d3.md).

A 5D3 conclui o escopo funcional local da etapa 5 e passou na CI remota; revisão e merge continuam pendentes. Evaluate/simulate continuam prévias administrativas; navegação e emissão usam avaliação real, papéis atuais, sid do servidor e `MfaProofs` quando exigido. Não há ALLOW automático nem prova sintética. O desafio orienta a autorização, sem bloquear toda verificação de senha ou substituir a barreira final dos tokens da 5D1. O recurso local aplica decisões por operação; recursos externos e revogação distribuída dependem da etapa 7. Client Credentials não recebe política de identidade humana. Remoção/substituição de fator ativo e regeneração de códigos permanecem fora do escopo atual. Confiança de navegador não é atestação de hardware; rede corresponde ao par conectado, sem confiar em forwarded headers. O contador de falhas de login tem aquecimento/cobertura local; tentativas MFA são persistidas por conta. Sessões e retornos de navegação continuam locais à instância. Operação multi-instância, revogação distribuída e retenção/expurgo precisam das entregas operacionais. A base até a 5D3 (`7713a94`) foi publicada em `feat/resource-operation-authorization`, com [CI aprovada](https://github.com/PauloHFA/SecureGate-IAM/actions/runs/39269486069) e 401 testes. A etapa 6 possui commits locais e ainda aguarda publicação/CI remota. Revisão/merge continuam pendentes.

## Etapa 6 — Eventos e Outbox

- [x] Criar envelope versionado e testes de contrato em `security-events` (6A1).
- [x] Incluir identificador, tipo, versão, horário, correlação, ator, contexto e dados dos seis produtores de integração (6A1/6A2).
- [x] Cobrir `auth.login.suspicious`, `auth.login.failed`, `token.revoked`, `session.terminated`, `policy.changed` e `user.locked` nos pontos de produção documentados da 6A2.
- [x] Gravar Outbox na mesma transação das mudanças auditadas e dos seis produtores, com rollback conjunto e payload imutável (6A1/6A2). Tentativa de login com persistência indisponível conserva erro genérico e histórico incompleto, sem prometer evento durável.
- [x] Publicar com confirmação, mandatory return, retry persistido e leases concorrentes (6B).
- [x] Validar topologia RabbitMQ, TTL de retry e DLQ com broker real na 6B; consumidores ainda pendentes.
- [x] Definir limpeza opcional de publicados, retenção, métricas e quarentena operacional da Outbox (6C).

**Aceite:** rollback não publica mudança não confirmada; queda do broker não perde evento persistido; recuperação publica pendências; produtor e consumidores respeitam o contrato.

Subdivisão: **6A1** — envelope v1, contrato JSON, V9 e Outbox atômica com eventos já auditados, implementada localmente na branch `feat/security-event-outbox`; ver [ADR-018](adr/018-envelope-e-outbox-transacional.md), [guia](runbooks/outbox-local.md) e [evidências](evidencias/etapa-06a1.md). **6A2** — dados validados e seis produtores reais, V10 para avaliação única de login, encerramento transacional e claim `authorization_id` estável no access token de usuário; implementada localmente em `feat/security-event-producers`. Ver [ADR-019](adr/019-produtores-de-eventos-de-seguranca.md), [guia](runbooks/eventos-seguranca-local.md) e [evidências](evidencias/etapa-06a2.md). **6B** — publicação opcional, V11 com leases/fencing e retry, confirmações/mandatory return, recuperação e topologia compartilhada; implementada localmente em `feat/outbox-rabbitmq-publisher`. Ver [ADR-020](adr/020-publicacao-outbox-rabbitmq.md), [guia](runbooks/publicacao-outbox-local.md) e [evidências](evidencias/etapa-06b.md). **6C** — V12, retenção opcional e limitada de publicados, métricas de backlog/entrega, quarentena e liberação com histórico, testes de falha/recuperação; implementada localmente em `feat/outbox-operations`. Ver [ADR-021](adr/021-operacao-da-outbox.md), [guia](runbooks/operacao-outbox-local.md) e [evidências](evidencias/etapa-06c.md). O escopo produtor/Outbox da etapa 6 está implementado localmente; publicação/CI remota e revisão continuam pendentes. Consumidores, deduplicação e efeitos distribuídos permanecem na etapa 7; o aceite integrado depende dessas entregas.

## Etapa 7 — Enforcer, Redis e revogação

- [x] Implementar consumidor de revogação com ack manual e idempotência por `eventId` (7A).
- [x] Aplicar efeito Redis antes de registrar conclusão/ACK na revogação (7A).
- [x] Implementar consumidor de bloqueio/política com a mesma garantia (7B).
- [x] Atualizar sessões, bloqueios por usuário/IP e revogação por `jti`/`sid`.
- [x] Calcular TTL conforme expiração do token, sessão ou bloqueio.
- [x] Tratar duplicação e eventos fora de ordem com regras de versão/timestamp.
- [x] Consultar bloqueio/revogação no recurso do `iam-core` e impedir acesso quando Redis falha (7C1).
- [x] Integrar sinais distribuídos e aplicação de bloqueios por limiar a login/emissão/refresh (7C2); os contadores são assíncronos, sem limite rígido de QPS.
- [x] Implementar rate limiting por conta/IP e logout/revogação ponta a ponta.
- [x] Medir propagação da confirmação da revogação até projeção Redis e rejeição no recurso protegido (7D1 local; nove amostras ociosas, sem garantia de produção).

**Aceite:** token anteriormente aceito passa a ser rejeitado em menos de 2 segundos no ambiente medido; integração cobre duplicação, reordenação e falhas temporárias.

Subdivisão: **7A** — bootstrap opcional do `policy-enforcer`, fila `enforcer.revocation`, contrato/cabeçalhos, script Redis atômico com fingerprint por eventId, efeitos terminais jti/authorizationId/sid, ACK manual, rejeição à DLQ e recuperação de indisponibilidade; implementada localmente em `feat/enforcer-revocation-consumer`. Ver [ADR-022](adr/022-consumidor-de-revogacao.md), [guia](runbooks/enforcer-revogacao-local.md) e [evidências](evidencias/etapa-07a.md). **7B** — consumidor de bloqueio/política, estado de identidade e versões exatas, contadores de limiar por conta/versão e IP, bloqueios e risco temporários; implementada localmente em `feat/enforcer-block-policy-consumer`. Ver [ADR-023](adr/023-consumidor-bloqueio-politica.md), [guia](runbooks/enforcer-bloqueio-politica-local.md) e [evidências](evidencias/etapa-07b.md). **7C1** — consulta opcional de revogação/identidade/bloqueios/watermarks no recurso do IAM, estado estrito e falha fechada, implementada localmente em `feat/resource-redis-enforcement`. Ver [ADR-024](adr/024-consulta-redis-no-recurso.md), [guia](runbooks/recurso-enforcement-redis-local.md) e [evidências](evidencias/etapa-07c1.md). **7C2** — login/emissão/refresh, sinais distribuídos, MFA real por risco e aplicação dos bloqueios projetados; implementada localmente em `feat/authentication-redis-enforcement`. Ver [ADR-025](adr/025-enforcement-login-emissao.md), [guia](runbooks/autenticacao-enforcement-redis-local.md) e [evidências](evidencias/etapa-07c2.md). **7D1** — baseline de revogação/logout com os JARs reais, correlação Outbox/Redis/HTTP e medição local; implementada em `feat/end-to-end-propagation`. Ver [ADR-026](adr/026-medicao-ponta-a-ponta.md), [guia](runbooks/propagacao-ponta-a-ponta-local.md) e [evidências](evidencias/etapa-07d1.md). **7D2** — duplicação, política fora de ordem, recuperação de falhas e bloqueios por limiar na cadeia completa, implementada localmente em `feat/end-to-end-resilience`. Ver [ADR-027](adr/027-resiliencia-ponta-a-ponta.md), [guia](runbooks/resiliencia-ponta-a-ponta-local.md) e [evidências](evidencias/etapa-07d2.md). O escopo funcional da etapa 7 está validado localmente e publicado na base 8B (`f005334`); CI remota e revisão continuam pendentes. Tombstones de sid continuam permanentes sem limite confiável dos grants; reconstrução Redis, alta disponibilidade, sessões compartilhadas e garantias sob carga não são comprovadas. Rate limiting permanece assíncrono, sem limite rígido de QPS.

## Etapa 8 — Auditoria e otimização

- [x] Criar área de ingestão de auditoria particionada por mês em `occurred_at` com Flyway (8A1; auditoria produtora legada preservada).
- [x] Usar chave `(id, occurred_at)` e criar partições futuras automaticamente (8A1; worker opcional, horizonte inicial Flyway).
- [x] Garantir deduplicação global de evento apesar das restrições de unicidade de tabelas particionadas; documentar a solução (8A1; recibo global transacional e fingerprint).
- [x] Restringir identidade de ingestão a INSERT/SELECT e separar permissões operacionais (8A2; pools dedicados; conexão principal proprietária preservada).
- [x] Implementar consumidor com lote e recuperação de falhas (8B; ACK após commit, DLQ para inválidos/conflitos).
- [x] Criar consulta por cursor estável, filtros e índices (8C; API administrativa opcional e V15).
- [x] Definir retenção/arquivamento preservando imutabilidade (8D1; arquivo no mesmo banco, sem exclusão/backup externo).
- [x] Medir N+1, planos SQL e ingestão com milhões de registros (8D2; 2.030.000 eventos/recibos sintéticos e benchmark optativo).

**Aceite:** duplicação não cria múltiplos registros; aplicação não altera/exclui auditoria; cursor lida com timestamps iguais; planos e medições documentados.

Subdivisão: **8A1** — V13 aditiva, `event_log` mensal, recibos globais, porta/adaptador de gravação e manutenção opcional, implementada localmente em `feat/partitioned-audit-foundation`. Ver [ADR-028](adr/028-base-auditoria-particionada.md), [guia](runbooks/auditoria-particionada-local.md) e [evidências](evidencias/etapa-08a1.md). **8A2** — V14, logins/pools dedicados, provisionamento administrativo separado, permissões e timeouts; implementada localmente em `feat/audit-database-privileges`. Ver [ADR-029](adr/029-identidades-auditoria.md), [guia](runbooks/identidades-auditoria-local.md) e [evidências](evidencias/etapa-08a2.md). **8B** — consumidor opcional em lote, confirmação após commit, DLQ para inválidos/conflitos e requeue/reconexão para falhas, implementado localmente em `feat/audit-batch-consumer`. Ver [ADR-030](adr/030-consumidor-auditoria-em-lote.md), [guia](runbooks/consumidor-auditoria-local.md) e [evidências](evidencias/etapa-08b.md). **8C** — consulta administrativa opcional por cursor/filtros e índices V15, implementada localmente em `feat/audit-cursor-query`. Ver [ADR-031](adr/031-consulta-auditoria-por-cursor.md), [guia](runbooks/consulta-auditoria-local.md) e [evidências](evidencias/etapa-08c.md). **8D1** — retenção quente/arquivo mensal no PostgreSQL, preservação de recibos e isolamento de eventos tardios, implementada localmente em `feat/audit-monthly-archive`. Ver [ADR-032](adr/032-arquivamento-mensal-auditoria.md), [guia](runbooks/arquivamento-auditoria-local.md) e [evidências](evidencias/etapa-08d1.md). **8D2** — harness optativo de ingestão/consulta, planos SQL e contagem de SELECTs, implementado e medido localmente em `perf/audit-scale-measurements`. Ver [ADR-033](adr/033-medicao-auditoria-em-escala.md), [guia](runbooks/medicao-auditoria-local.md) e [evidências](evidencias/etapa-08d2.md). A 8A1 não migra a auditoria legada, não conecta consumidor e não conclui o aceite da etapa 8. Proprietário/superusuário ainda pode alterar DDL; a conexão principal do IAM continua proprietária/migradora, enquanto os caminhos dedicados são restritos. O consumidor da 8B permanece desabilitado por padrão e exige ingestão dedicada.

## Etapa 9 — Vault avançado e PAM

- [x] Integrar KV e credenciais temporárias de banco (KV no IAM na 9A1; AppRole runtime/KV separado e pool temporário na 9A2b; KV do enforcer na 9A3).
- [x] Renovar token/lease do pool PostgreSQL e reemitir credenciais após limites/falhas (9A2c; ciclos Transit/KV independentes).
- [x] Testar rotação de credenciais com pool de conexões (9A2c, incluindo transação fixada e drenagem limitada).
- [x] Emitir credenciais Vault para diagnóstico restrito com escopo/MFA, justificativa, TTL e revogação recuperável (9B2; sessões abertas dependem da recuperação do Vault para revogação sob falha).
- [x] Registrar pedidos autorizados com justificativa, MFA obrigatório, TTL, consulta/revogação próprias e auditoria transacional (9B1).
- [x] Auditar fatos PAM de emissão, expiração lógica, falha, recusa autenticada e confirmação de revogação sem registrar segredo (9C1).
- [x] Persistir retries da limpeza PAM e medir pendências/resultados incertos sem descartar leases (9C2a).
- [x] Recuperar observador de pedidos e testar falha de auditoria/reinício real da JVM preservando agenda, prazo e correlação (9C2b).
- [x] Completar jornada PAM HTTP/credencial/ingestão/consulta e aceite local da auditoria com falhas/reinício e identidades separadas (9C3; não encerra o aceite global da etapa 9).
- [x] Documentar entrega local da credencial inicial de autenticação do serviço no Vault (arquivos AppRole; entrega de produção depende de hardening).
- [x] Criar runbook de indisponibilidade e recuperação (9A2c; pausa/retomada medida, perda do Vault dev exige ação administrativa).

**Aceite:** leases expiram/renovam corretamente; pedido não autorizado falha; segredos não ficam em arquivos versionados, imagens ou logs.

Subdivisão: **9A1** — snapshot KV v2 opcional no startup do IAM, AppRole por arquivo, precedência para credenciais permitidas, falha sem fallback e revogação do token de leitura; implementada localmente em `feat/vault-kv-bootstrap`. Ver [ADR-034](adr/034-bootstrap-kv-vault.md), [guia](runbooks/vault-kv-local.md) e [evidências](evidencias/etapa-09a1.md). **9A2** — credenciais PostgreSQL temporárias, separação migração/runtime, leases e renovação/rotação dos pools. **9B** — acesso privilegiado com autorização, justificativa e TTL. **9C** — auditoria operacional e recuperação integrada. A 9A1 não renova segredos em execução, não integra KV no enforcer e não conclui o aceite da etapa 9.

A 9A2 foi dividida em **9A2a**, perfil de permissões NOLOGIN e validação do IAM sem propriedade das tabelas; **9A2b**, Database Engine Vault, emissão temporária e separação automatizada migração/runtime; e **9A2c**, leases, renovação e troca segura de pools. A 9A2a está implementada localmente em `feat/runtime-database-privileges`: SQL/script administrativo, grants explícitos e V17 para o histórico de quarentena via trigger. Ver [ADR-035](adr/035-permissoes-postgresql-runtime.md), [guia](runbooks/permissoes-runtime-local.md) e [evidências](evidencias/etapa-09a2a.md). O startup padrão ainda usa a conta proprietária; a 9A2a não emite credenciais nem integra o Database Engine.

A **9A2b** acrescenta modo opcional de DataSource temporário, AppRole runtime sem acesso ao KV proprietário, comando administrativo `--migrate-only`, provisionamento/rotação do emissor dedicado e encerramento do pool antes do prazo. Implementada localmente em `feat/vault-dynamic-database`. Ver [ADR-036](adr/036-credenciais-dinamicas-postgresql.md), [guia](runbooks/vault-database-local.md) e [evidências](evidencias/etapa-09a2b.md). O modo anterior continua padrão; no modo novo, Flyway é desativado no runtime. Esse ciclo inicial exigia reinício ao vencer o lease e foi substituído pela 9A2c abaixo. O aceite operacional da etapa 9 continua pendente.

A **9A2c** substitui o ciclo de lease único: renova token/lease, reautentica após limite/falha, troca gerações validadas e drena pools por prazo limitado. Watchdog e limites de gerações mantêm falha fechada, com recuperação local após pausa do Vault. Implementada localmente em `feat/vault-database-lease-lifecycle`. Ver [ADR-037](adr/037-renovacao-e-troca-de-pools.md), [guia](runbooks/vault-database-renovacao-local.md) e [evidências](evidencias/etapa-09a2c.md). Não exige reiniciar a cada TTL, mas não garante transações longas ou disponibilidade sob falhas persistentes de limpeza. Na conclusão da 9A2c, PAM, auditoria integrada, KV do enforcer e aceite global permaneciam pendentes; as entregas posteriores estão registradas abaixo.

A **9B1** acrescenta pedidos persistidos com V18, escopo `pam:request`, alvo `database:diagnostics` e MFA obrigatório do sid original; criação e revogação possuem auditoria/Outbox atômicas, sem justificativa nos eventos. Implementada localmente em `feat/privileged-access-requests`, com commit local. Ver [ADR-038](adr/038-pedidos-de-acesso-privilegiado.md), [guia](runbooks/pedidos-privilegiados-local.md) e [evidências](evidencias/etapa-09b1.md). A **9B2** abaixo acrescenta a emissão em alvo dedicado no Vault, consumo/reautorização do pedido e revogação real; `AUTHORIZED` na 9B1 não concede acesso PostgreSQL nem é uma credencial.

A **9B2** acrescenta V19, emissão única concorrente com reautorização e escopo `pam:issue`, AppRole/mount/emissor separados e diagnóstico restrito. Há revogação administrativa e worker que retoma leases conhecidos após falhas/reinício. Implementada inicialmente em `feat/pam-vault-credentials`, sobre `feaf718`, e incluída com a 9C1 no commit de `feat/pam-operational-audit`. Ver [ADR-039](adr/039-emissao-pam-no-vault.md), [guia](runbooks/credenciais-pam-local.md) e [evidências](evidencias/etapa-09b2.md). Senhas não são persistidas nem reentregues. VALID UNTIL limita novas autenticações; uma sessão ativa pode ultrapassar o prazo com Vault indisponível. O diagnóstico não recebe privilégios do runtime e não é superusuário. A 9C e o aceite final continuam pendentes.

A **9C1** acrescenta V20, origem de correlação e marcadores transacionais de expiração de pedidos/credenciais, fatos de prazo lógico separados do reconhecimento do Vault e recusas de ator verificado com códigos fixos. Implementada localmente em `feat/pam-operational-audit`, incluída no mesmo commit da 9B2 e da consolidação documental, sobre a base `feaf718`. Ver [ADR-040](adr/040-auditoria-operacional-pam.md), [guia](runbooks/auditoria-pam-local.md) e [evidências](evidencias/etapa-09c1.md). A **9C2** tratará recuperação/observabilidade; a **9C3** validará ingestão/consulta e jornada integrada. Na conclusão da 9C1, KV do enforcer e aceite global ainda estavam pendentes.

A **9C2a** acrescenta V21, intervalos persistidos de retry de 2–60 s, seleção de itens elegíveis, avanço do lote durante falhas e métricas de fila com disponibilidade/frescor do snapshot. Implementada em `feat/pam-cleanup-recovery`, versionada localmente em `f11e18d`, sobre `54b5bea`. Ver [ADR-041](adr/041-recuperacao-da-limpeza-pam.md), [guia](runbooks/recuperacao-pam-local.md) e [evidências](evidencias/etapa-09c2a.md). O contador satura sem descartar o lease; recuperação não reemite senha nem reabre estado terminal. A divisão **9C2b** abaixo completa o observador de pedidos e o exercício de reinício da JVM; a 9C3 continua responsável pelo aceite integrado. Não há reconciliação automática após perda do Vault dev nem retenção automática de metadados.

A **9C2b** acrescenta V22 e retry persistido por pedido, isolamento transacional/continuidade do lote e métricas do observador. O teste com JAR real provoca falha de auditoria, confirma agenda/rollback, encerra a JVM e inicia outra no mesmo banco/Vault, preservando prazo/correlação e produzindo um fato com Outbox. Implementada em `feat/pam-request-recovery`, versionada localmente em `bb48f3f`, sobre `f11e18d`. Ver [ADR-042](adr/042-recuperacao-do-observador-pam.md), [guia](runbooks/recuperacao-pam-local.md) e [evidências](evidencias/etapa-09c2b.md). O exercício usa fixture SQL com emissão desabilitada; a jornada humana HTTP/credencial/ingestão/consulta permanece na 9C3. A 9C2 está implementada localmente por 9C2a/9C2b, sem concluir o aceite da etapa 9.

A **9C3** integra a jornada com JARs reais, OAuth/PKCE/MFA, emissão/uso de credencial PostgreSQL, revogação após pausa do Vault/reinício do IAM e expiração com RabbitMQ indisponível. Fatos acumulados na Outbox chegam à ingestão dedicada e à consulta central/cursor após recuperação, sem pressupor ordem de entrega. Runtime e ingestão usam identidades separadas; a credencial humana não lê identidades. Corrige também o bootstrap OAuth que exigia literalmente flywayInitializer mesmo com migração externa/Flyway desabilitado. Versionada localmente em `a9b67f0`, na branch `feat/pam-integrated-acceptance`, sobre `bb48f3f`; sem nova migração. Ver [ADR-043](adr/043-aceite-integrado-pam.md), [guia](runbooks/jornada-pam-integrada-local.md) e [evidências](evidencias/etapa-09c3.md). O login runtime do teste é sintético/restrito; renovação dinâmica continua coberta separadamente na 9A2c. O KV do enforcer é concluído na 9A3 e a revisão local na 9D abaixo; aceite global/remoto permanece pendente.

A **9A3** integra snapshot KV v2 no startup do enforcer, com AppRole por arquivo, campos permitidos, precedência, revogação do token e falha sem fallback. O launcher combina -EnableConsumer e -EnableVaultKv. Testes usam ACL real e consumo RabbitMQ/projeção Redis com credenciais locais inválidas. Sem nova migração ou refresh em execução. Implementação local em `feat/enforcer-vault-kv`, sobre `a9b67f0`, versionada em `6d08c2a`; publicação/CI pendentes. Ver [ADR-044](adr/044-bootstrap-kv-enforcer.md), [operação](runbooks/enforcer-vault-kv-local.md), [evidências](evidencias/etapa-09a3.md). A revisão de controles é consolidada na 9D abaixo; aceite global/remoto permanece pendente e hardening geral de produção continua na etapa 11.

A **9D** consolida a revisão de controles e o **aceite técnico local**, usando a base `6d08c2a` e os 778 testes já aprovados na 9A3. Matriz liga critérios a testes/código/ACL e registra riscos residuais. Não houve novo build ou alteração funcional. Ver [matriz](runbooks/aceite-etapa9.md) e [evidências](evidencias/etapa-09d.md). O aceite global/remoto continua pendente de publicação/CI e verificações de entrega aplicáveis; ausência de segredos em imagens será verificada na etapa 11. Próxima implementação local: 10A. Revisão versionada em `255fdff`, na branch `docs/stage-nine-acceptance`; publicação/CI pendentes.

## Etapa 10 — Carga e observabilidade

- [x] Criar planos JMeter e massa sintética reproduzível de usuários/clientes para smoke (10A1/10A2a/10A2b1–3); perfis sustentados e medições permanecem em 10C.
- [x] Cobrir funcionalmente em smoke Client Credentials (10A1), PKCE/replay (10A2a), falhas de login (10A2b1), recursos/revogação (10A2b2) e auditoria (10A2b3); aceite de desempenho pendente.
- [x] Instrumentar métricas, logs estruturados e tracing propagado por HTTP/RabbitMQ (validação funcional local; logs nos processos).
- [x] Subir Prometheus/Grafana e correlacionar latência, filas, banco, Redis e Vault (seis scrapes e dashboards na fixture descartável).
- [ ] Executar ramp-up, patamar e ramp-down, registrando recursos do ambiente.
- [ ] Medir taxa efetiva: 300 usuários virtuais não equivalem a 300 requisições/s.
- [ ] Gerar relatórios HTML e documentar gargalos/ajustes.

**Aceite:** metas abaixo medidas, sem crescimento contínuo de memória ou acúmulo sustentado de filas. Desvios exigem evidência e plano de correção, sem declarar cumprimento não medido.

Subdivisão: **10A1** — gerador de clientes sintéticos pela API, plano Client Credentials com sucesso/invalid_client separados, runner com verificação de asserções, resultados sanitizados e smoke opt-in descartável. Baseline JMeter 5.6.3; não há benchmark de capacidade ou alteração de aplicação/schema. Implementação local em `feat/load-client-credentials-smoke`, sobre `255fdff`, incluída neste commit; publicação/CI a verificar separadamente. Ver [ADR-045](adr/045-base-de-carga-jmeter.md), [operação](../load-tests/jmeter/README.md), [evidências](evidencias/etapa-10a1.md). **10A2a** — massa humana, PKCE e replay; **10A2b** — demais cenários; **10B** — observabilidade; **10C** — medição; **10D** — ajustes. A 10A1 não conclui os itens/aceite globais da etapa 10.

A **10A2a** acrescenta usuários/senhas sintéticos, clientes de navegador e política limitada por API, plano JSR223 PKCE com cookies/CSRF/state, conferência de identidade/TTL e rejeição de código reutilizado. O teste opt-in também exige HTTP 400 para verifier incorreto e artefatos sem credenciais da fixture. Cada amostra é uma jornada agregada; não é medição de /oauth2/token ou assinatura JWT. Implementação local em `feat/load-human-pkce-smoke`, sobre `1e7a201`, incluída neste commit; push pendente. Ver [ADR-046](adr/046-carga-humana-pkce.md), [operação](../load-tests/jmeter/HUMAN-PKCE.md), [evidências](evidencias/etapa-10a2a.md). A 10A2b continua responsável pelos demais cenários; a 10A/etapa 10 ainda não estão concluídas.

A **10A2b1** acrescenta massa de usuários por API, plano de senha incorreta/usuário inexistente com cookies/CSRF reais, mensagem genérica comparada e sessão anônima confirmada. O teste opt-in exige dois fatos reais de falha na Outbox, ausência do candidato no banco e detecção de sessão autenticada na injeção de senha correta. Padrão pequeno distingue recusa de credenciais de 403/429/503, que falham o contrato. Não mede tempo constante/capacidade nem ingestão central sob carga. Implementação local em `feat/load-login-failures-smoke`, sobre `8580b4e`, incluída neste commit; push pendente. Ver [ADR-047](adr/047-carga-de-falhas-de-login.md), [operação](../load-tests/jmeter/LOGIN-FAILURES.md), [evidências](evidencias/etapa-10a2b1.md). A **10A2b2** acrescenta cinco rótulos: PKCE, recurso aceito, token inválido, revogação e recusa posterior com validade residual. Reutiliza a massa humana com política de conta opt-in, mantém tokens em memória e usa Bearer sem cookies. O teste isolado verifica publicação/projeção fora do cronômetro e detecta revogação omitida. HTTP 401 também decorre do ledger local, portanto não mede propagação distribuída isolada. Ver [ADR-048](adr/048-carga-de-recurso-e-revogacao.md), [operação](../load-tests/jmeter/RESOURCE-REVOCATION.md) e [evidências 10A2b2](evidencias/etapa-10a2b2.md). Implementação local em feat/load-resource-revocation-smoke, sobre 860d125, incluídas neste commit; push pendente. A **10A2b3** acrescenta fatos sintéticos gerados pela API administrativa, consulta central por tipo/correlação, duas páginas com cursor real e recusa sem credencial. Teste opt-in usa consumidor e pool restrito, confere envelopes centrais versus Outbox e detecta correlação inexistente com 200 vazio. Produção inclui hashing/escritas; polling não mede ingestão isolada/capacidade. Ver [ADR-049](adr/049-carga-de-auditoria-central.md), [operação](../load-tests/jmeter/AUDIT.md) e [evidências](evidencias/etapa-10a2b3.md). Trabalho atual em feat/load-audit-smoke, sobre 860d125, preserva 10A2b2 e 10A2b3 incluídas neste commit; push pendente. A 10A reúne planos funcionais dos cenários previstos; **10B–10D** e o aceite da etapa 10 continuam pendentes.

A **10B** está dividida em **10B1** (inventário/exportação IAM), **10B2** (coleta/dashboards/enforcer/dependências) e **10B3** (logs estruturados e tracing HTTP/RabbitMQ). A **10B1** implementa registry Prometheus gerenciado pelo BOM, endpoint opt-in com token METRICS_READ em arquivo privado e histogramas HTTP opcionais (1 ms–10 s), sem reutilizar token administrativo. Testes verificam isolamento, padrão desligado e métricas sem query/credenciais sintéticas. O enforcer continua sem HTTP; stack de coleta/tracing não implementada. Ver [inventário](OBSERVABILIDADE.md), [ADR-050](adr/050-exportacao-prometheus-protegida.md), [runbook](runbooks/metricas-prometheus-local.md) e [evidências](evidencias/etapa-10b1.md). Trabalho local em feat/iam-prometheus-metrics, sobre 42b9707, incluída neste commit; push pendente. Itens globais de observabilidade e aceite de desempenho permanecem abertos.

A **10B2** foi dividida em **10B2a** (coleta/dashboard IAM) e **10B2b** (enforcer/dependências). A **10B2a** entrega Compose separado com Prometheus/Grafana por versão/digest, portas loopback, credenciais por arquivo, datasource e dashboard IAM de 12 painéis. Smoke opt-in usa IAM/JARs reais e os arquivos versionados copiados, confere parsing das expressões, frame de datasource, ausência de acesso anônimo, falha 401/up=0 e recuperação com token original. Não modifica runtime Java, schema, grants/ACL ou stack persistente. Ver [ADR-051](adr/051-coleta-e-dashboard-local-do-iam.md), [operação](runbooks/coleta-iam-local.md), [evidências](evidencias/etapa-10b2a.md) e [inventário](OBSERVABILIDADE.md). Trabalho local em feat/iam-observability-stack, sobre 9f5f857, incluída neste commit; push pendente. Não conclui o item de correlação com todas as dependências, logs/tracing ou aceite de desempenho.

A **10B2b** foi dividida em **10B2b1** (enforcer) e **10B2b2** (exporters das dependências). A **10B2b1** implementa listener JDK opcional, loopback, GET /metrics e token próprio de leitura; preserva Spring non-web e padrão sem listener. Integra job/overlay opcional, dashboard separado e migração de configuração 10B2a com backups sem trocar senha/volumes. Build do módulo verifica segurança/default/lifecycle/consumo/arquitetura/cobertura. Smoke real exige user.locked publicado/processado, projeção Redis e contador ACK no Prometheus/Grafana, com falha de token isolada do IAM e recuperação. Ver [ADR-052](adr/052-metricas-opcionais-do-enforcer.md), [operação](runbooks/metricas-enforcer-local.md) e [evidências](evidencias/etapa-10b2b1.md). Trabalho local em feat/enforcer-prometheus-metrics, sobre 123c4b0, incluída neste commit; push pendente. Não conclui exporters, tracing/logs ou aceite de capacidade.

A **10B2b2** foi dividida em **10B2b2a** (RabbitMQ), **10B2b2b** (PostgreSQL), **10B2b2c** (Redis) e **10B2b2d** (Vault). A **10B2b2a** entrega plugin nativo opcional por overlay, porta loopback, scrape agregado a cada 15 s, migração com backup sem novas credenciais e dashboard separado de seis painéis. Smoke descartável verifica mensagens AMQP ready/unacked/ACK, consultas/frame Grafana e pausa/recuperação do scrape. Endpoint local sem autenticação, dependente de isolamento do host/rede Docker. Não modifica Java/schema/topologia ou a stack persistente. Ver [ADR-053](adr/053-coleta-opcional-rabbitmq.md), [operação](runbooks/metricas-rabbitmq-local.md), [evidências](evidencias/etapa-10b2b2a.md). Trabalho local em feat/rabbitmq-prometheus-metrics sobre 7b92e4f, incluída neste commit; push pendente; aceite completo 10B continua pendente.

A **10B2b2b** implementa login próprio pg_monitor, senha por arquivo e exporter 0.19.1 fixado por digest em overlay opcional loopback. Provisionamento transacional recusa ACLs/memberships/atributos inesperados sem modificar os logins da aplicação. Coletores limitados a database/stat_database/stat_bgwriter, sem tabelas/SQL de negócio. Job/migração com backup e dashboard de seis painéis distinguem up HTTP de pg_up SQL. Smoke exige ACL real com read-only desligado, estatísticas/transações/Grafana, credencial inválida com HTTP disponível e recuperação. Ver [ADR-054](adr/054-coleta-opcional-postgresql.md), [operação](runbooks/metricas-postgres-local.md), [evidências](evidencias/etapa-10b2b2b.md). Trabalho local em feat/postgres-prometheus-metrics sobre eea6f52, incluída neste commit; push pendente. pg_monitor lê estatísticas amplas do cluster; não equivale a isolamento por banco nem mede capacidade.

A **10B2b2c** implementa ACL Redis dedicada sem acesso a chaves/canais, arquivos privados com hashes/senha JSON e overlay que mantém autenticação default/AOF e acrescenta redis_exporter 1.95.0 fixado por digest. Preparador verifica ACL ativa e recusa usuários/permissões inesperados sem alterar o serviço. Job/migração com backup e dashboard de seis painéis distinguem HTTP up de redis_up; SLOWLOG GET é negado deliberadamente. Smoke exige recusas de dados/admin, preservação após reinício, Grafana e falha/recuperação de senha. Ver [ADR-055](adr/055-coleta-opcional-redis.md), [operação](runbooks/metricas-redis-local.md), [evidências](evidencias/etapa-10b2b2c.md). Trabalho local em feat/redis-prometheus-metrics sobre 6a48bb6, incluída neste commit; push pendente. Aplicação da ACL requer reinício local; não houve alteração da stack persistente ou Java/migrations.

A **10B2b2d** implementa overlay que restringe o listener dev anônimo ao container e publica métricas autenticadas, política mínima sys/metrics e token próprio de 1h sem default/renovação. Provisionador recusa listener anônimo/política/token divergentes; reusa válido e rotaciona com arquivo atômico/revogação antiga e compensação limitada. Job/overlay da coleta, migração com backups e dashboard de seis painéis. Smoke verifica recusas reais, TTL, Grafana, erro/403/recuperação e rotação preservando Transit/KV/política após startup protegido. Aplicar overlay em Vault dev existente exige reset com perda de estado; não aplicado na stack persistente. Ver [ADR-056](adr/056-coleta-autenticada-vault-dev.md), [operação/matriz de correlação](runbooks/metricas-vault-local.md) e [evidências](evidencias/etapa-10b2b2d.md). Trabalho local em feat/vault-prometheus-metrics sobre c55446d, incluída neste commit; push pendente. Contratos individuais de quatro dependências implementados, sem comprovar coleta conjunta sustentada/capacidade; aceite 10B/etapa 10 permanece aberto.

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

## Situação atual e próxima entrega

A 10B4 foi dividida em backend (a), exportação (b) e validação integrada (c), antes de **10C — medição**. A **10B4a** entrega Tempo/Grafana opcionais, volume/retenção configurada e portas loopback; validação registrada com 847 testes, smoke dos scripts e ressalva de shutdown. A **10B4b** implementa exporter OTLP opcional nos providers IAM/enforcer, destino local validado, transporte JDK sem redirects, fila 512/lote 128 e timeouts curtos sem retry. Build da 10B4b: **886 testes aprovados**, zero falhas/erros/skips, dois gates JaCoCo e arquitetura atendidos. Corrigido também bloqueio de abort NIO do enforcer, reproduzido com RabbitMQ real e entrega sem ACK recuperada por outro worker; ressalva histórica do fork IAM permanece. [ADR-065](adr/065-exportacao-otlp-local.md), [operação](runbooks/exportacao-otlp-local.md), [evidências](evidencias/etapa-10b4b.md).

A 10B4c agora comprova cadeia real de negócio consultável no Tempo (HTTP IAM → outbox/produtor → consumidor enforcer), raiz de auditoria com links, proxy Grafana autenticado e continuidade do efeito Redis durante pausa do receptor. Após retorno, dez eventos espaçados em 2 s tiveram projeção e trace consultáveis. A seleção integrada passou em 81,88 s; o `verify` completo passou com 887 testes, dois gates de cobertura e arquitetura atendidos. Trata-se de patamar funcional curto, sem prova de capacidade, retenção de 24 h ou coleta simultânea sustentada de Prometheus, logs, traces e todos os exporters. **Aceite global 10B continua aberto** até essa coleta conjunta; 10C continua responsável pela medição de desempenho. Sem schema/credenciais/stack persistente alterados. Trabalho em feat/tracing-backend sobre 81aeffa, versionado em commit local; push/CI remota pendentes. A ressalva histórica de shutdown Hikari não foi declarada corrigida. [Backend 10B4a](runbooks/backend-tracing-local.md), [evidência integrada](evidencias/etapa-10b4c.md).

A **10B5a** divide o aceite global restante e valida coleta simultânea do núcleo: Prometheus IAM/enforcer, logs JSON locais e traces Tempo/Grafana em 12 bloqueios durante 1 min. Dois jobs com `up=1` ao longo do patamar, pelo menos oito amostras recentes por serviço, 12 ACKs e traces de início/meio/fim consultáveis. Seleção local: um teste aprovado, `BUILD SUCCESS` em 2 min 17 s; `verify` completo: 888 testes aprovados, dois gates de cobertura e arquitetura atendidos. O próximo passo **10B5b** reúne os exporters PostgreSQL, Redis, RabbitMQ e Vault na mesma execução e registra a decisão do aceite global 10B; logs locais ainda não equivalem a agregação central. Sem medição de capacidade ou alteração da stack persistente. Trabalho em feat/tracing-backend sobre cc95318, versionado em commit local; push/CI remota pendentes. [Operação](runbooks/coleta-conjunta-nucleo-local.md), [evidência](evidencias/etapa-10b5a.md).

A **10B5b** integra PostgreSQL, Redis, RabbitMQ e Vault aos scrapes IAM/enforcer do mesmo teste, com credenciais efêmeras e permissões mínimas. Durante 12 bloqueios em 1 min, os seis jobs permaneceram saudáveis, com `pg_up=1`, `redis_up=1`, Vault desbloqueado, série de fila RabbitMQ, painéis autenticados, traces e logs locais restritos. Seleção: 1 teste aprovado, `BUILD SUCCESS` em 2 min 46 s; `verify` completo: 888 testes aprovados e `BUILD SUCCESS` em 15 min 23 s, com dois gates de cobertura e arquitetura atendidos. A integração funcional local dos exporters está atendida. O aceite operacional global 10B permanece parcial porque não há agregação central dos logs nem comprovação de retenção/estabilidade prolongada; capacidade fica para 10C. Implementação versionada em commit local; push/CI remota pendentes e stack persistente preservada. [Operação](runbooks/coleta-conjunta-dependencias-local.md), [evidência](evidencias/etapa-10b5b.md).

A **10C1** inicia a medição com um plano JMeter exclusivo de emissão `client_credentials`: três processos sequenciais para aquecimento, patamar e redução, percentis/taxa por fase e amostragem opcional de CPU/memória IAM. O ensaio opt-in descartável 3/5/3 s passou com 151 emissões válidas e zero falhas; no patamar, 96 emissões, 19,2 req/s e p95 101 ms. Esta taxa fica abaixo da referência de 300 req/s e duas threads não oferecem carga suficiente para inferir capacidade máxima. A 10C2 deve medir patamares longos, recursos de todas as dependências, validação, revogação e auditoria. Implementação versionada em commit local; push e CI remota pendentes. [Operação](runbooks/medicao-emissao-token-local.md), [evidência](evidencias/etapa-10c1.md).

# SecureGate IAM

Plataforma de identidade e acesso baseada em Zero Trust, com provisionamento, OAuth/OIDC, políticas contextuais, eventos de segurança e auditoria.

Documentação: [índice](docs/README.md), [implementação passo a passo e subdivisões](docs/GUIA-DE-IMPLEMENTACAO.md) e [plano com critérios de aceite](docs/PLANO-DE-IMPLEMENTACAO.md).

## Estado atual

Fundação, infraestrutura e API administrativa de Identity/Access implementadas. O core gerencia usuários, credenciais Argon2id, grupos, papéis, permissões e auditoria transacional. Administração fica desabilitada por padrão e exige ativação explícita com token bootstrap temporário. A etapa 3 teve 106 testes no aceite original e está incluída na base publicada até a 5A. Consulte as [evidências históricas](docs/evidencias/etapa-03.md).

A entrega 4A acrescenta assinatura RS256 via Vault Transit e JWKS; a 4B integra Client Credentials/JDBC. A 4C acrescenta login, clientes web confidenciais e Authorization Code com PKCE S256 e consentimento protegido. A 4D1 implementa refresh opcional com rotação, janela absoluta de 30 minutos, detecção de reuso, introspecção e revogação pelo cliente proprietário. A 4D2 adiciona discovery, ID token Transit e userinfo com claims limitadas aos escopos aprovados. Access tokens e ID tokens duram cinco minutos. Consulte os guias [PKCE](docs/runbooks/oauth-pkce-local.md), [refresh](docs/runbooks/oauth-refresh-local.md) e [OIDC](docs/runbooks/oidc-local.md), com validação registrada nas [evidências 4D2](docs/evidencias/etapa-04d2.md).

A 5A acrescenta políticas JSONB versionadas e auditadas e uma avaliação administrativa por papéis atuais. O motor nega por padrão, faz DENY prevalecer e combina exigência de MFA com limites de escopo/TTL. A prévia administrativa permanece separada da aplicação à emissão de tokens da 5D1. Consulte o [guia de políticas](docs/runbooks/politicas-local.md) e as [evidências 5A](docs/evidencias/etapa-05a.md).

A 5B1 adiciona condições ABAC tipadas na gramática v2, risco determinístico de 0–100 e cenários administrativos separados da avaliação normal. Documentos v1 permanecem compatíveis. Consulte o [guia ABAC/risco](docs/runbooks/abac-risco-local.md) e as [evidências 5B1](docs/evidencias/etapa-05b1.md).

A 5B2 coleta contexto de navegador autenticado, rede da conexão, falhas reais de login e sensibilidade administrada de recursos. Aprovação de navegador tem prazo e versão; contextos de outro usuário, antigos ou fechados não fornecem confiança. Há cinco minutos de aquecimento do contador após iniciar o processo. Consulte o [guia de coleta](docs/runbooks/coleta-risco-local.md) e as [evidências 5B2](docs/evidencias/etapa-05b2.md).

A 5C1 inicia MFA com TOTP e proteção da semente por uma chave AES-256-GCM separada no Vault Transit, vinculada ao usuário e à inscrição. A 5C2 acrescenta cadastro/ativação em `/mfa`, recuperação de uso único, limite de tentativas por conta e prova de step-up de cinco minutos vinculada à sessão. Consulte o [guia MFA](docs/runbooks/mfa-local.md), as [evidências 5C2](docs/evidencias/etapa-05c2.md) e a [base criptográfica](docs/runbooks/mfa-base-local.md).

A 5D1 exige política para emissão e renovação de tokens de usuário: alvo `oauth:<client_id>`, ação `issue`. Sem permissão correspondente, a troca é negada; limites reduzem escopos/TTL e MFA exige prova no sid original. Isso vale também para clientes existentes, sem ALLOW automático. Consulte o [guia de políticas na emissão](docs/runbooks/politicas-tokens-local.md) e as [evidências 5D1](docs/evidencias/etapa-05d1.md).

A 5D2 integra o desafio MFA à navegação de autorização, antes do consentimento e novamente na aprovação. O retorno exige CSRF, ticket de uso único, prazo e a mesma identidade/sessão; preserva state, nonce e PKCE e revalida a autorização. A 5D3 acrescenta a API Bearer por operação, descrita abaixo. Consulte o [guia do desafio MFA](docs/runbooks/desafio-mfa-local.md) e as [evidências 5D2](docs/evidencias/etapa-05d2.md).

A 6A1 adiciona o envelope de eventos v1 e a Outbox PostgreSQL: cada nova auditoria produz um evento na mesma transação, com ID/horário compartilhados e rollback conjunto. Os eventos persistidos são a entrada do publicador da 6B. Consulte o [guia da Outbox](docs/runbooks/outbox-local.md), a [ADR-018](docs/adr/018-envelope-e-outbox-transacional.md) e as [evidências 6A1](docs/evidencias/etapa-06a1.md).

A 6A2 integra os seis produtores de segurança, preserva a auditoria legada e valida seus dados: tentativa/suspeita de login, mudança de política, bloqueio administrativo, revogação e encerramento de sessão. Access tokens novos de usuário recebem `authorization_id` estável entre refreshes. Consulte o [guia de eventos](docs/runbooks/eventos-seguranca-local.md), a [ADR-019](docs/adr/019-produtores-de-eventos-de-seguranca.md) e as [evidências 6A2](docs/evidencias/etapa-06a2.md).

A 6B acrescenta publicação opcional no RabbitMQ com mensagens persistentes, confirmação/mandatory return, leases concorrentes e retry na Outbox. Use `-EnableOutboxPublisher` para ativar. A entrega preserva eventId/payload nas tentativas e cobre falha/recuperação com broker real. Consulte o [guia de publicação](docs/runbooks/publicacao-outbox-local.md), a [ADR-020](docs/adr/020-publicacao-outbox-rabbitmq.md) e as [evidências 6B](docs/evidencias/etapa-06b.md).

A 6C acrescenta métricas administrativas de backlog, quarentena com histórico e limpeza opcional de publicações confirmadas. Use `-EnableOutboxRetention` para ativar a retenção de sete dias; pendências e auditoria são preservadas. Consulte o [guia operacional](docs/runbooks/operacao-outbox-local.md), a [ADR-021](docs/adr/021-operacao-da-outbox.md) e as [evidências 6C](docs/evidencias/etapa-06c.md).

A 7A inicia o `policy-enforcer` com consumo opcional da fila de revogação, ACK manual após efeito Redis e deduplicação atômica. Projeta jti, família de autorização e sid encerrado, sem reduzir prazos em eventos fora de ordem. A consulta no recurso passa a ser opcional na 7C1, descrita abaixo. Consulte o [guia do consumidor](docs/runbooks/enforcer-revogacao-local.md), a [ADR-022](docs/adr/022-consumidor-de-revogacao.md) e as [evidências 7A](docs/evidencias/etapa-07a.md).

A 7B acrescenta a fila de bloqueio/política ao mesmo processo: status e versões exatas de identidade/política, contadores limitados por conta/versão e IP e sinais temporários de risco. Eventos antigos não revertem desbloqueio nem ressuscitam sinais expirados. Consulte o [guia de bloqueio/política](docs/runbooks/enforcer-bloqueio-politica-local.md), a [ADR-023](docs/adr/023-consumidor-bloqueio-politica.md) e as [evidências 7B](docs/evidencias/etapa-07b.md).

A 7C1 adiciona `-EnableResourceEnforcement`: o recurso humano consulta revogação, identidade, bloqueios e versões de política no Redis, preservando verificações locais. Falha de consulta impede acesso com 503; bloqueio temporário usa 429. A 7C2 estende o modo ao login e à emissão/refresh humanos. Consulte o [guia](docs/runbooks/recurso-enforcement-redis-local.md), a [ADR-024](docs/adr/024-consulta-redis-no-recurso.md) e as [evidências 7C1](docs/evidencias/etapa-07c1.md).

A 7C2 aplica sinais Redis no login e na emissão/refresh, preserva códigos e refresh tokens após recusa transitória e exige prova MFA real diante de risco. Consulte o [guia de autenticação](docs/runbooks/autenticacao-enforcement-redis-local.md), a [ADR-025](docs/adr/025-enforcement-login-emissao.md) e as [evidências 7C2](docs/evidencias/etapa-07c2.md).

A 7D1 acrescenta o módulo de testes ponta a ponta, executando IAM/enforcer empacotados com infraestrutura descartável. Mede revogação por jti, família após refresh e logout, correlacionando Outbox, Redis e rejeição HTTP. Consulte o [guia](docs/runbooks/propagacao-ponta-a-ponta-local.md), a [ADR-026](docs/adr/026-medicao-ponta-a-ponta.md) e as [evidências 7D1](docs/evidencias/etapa-07d1.md).

A 7D2 valida reinício do enforcer, indisponibilidade do broker/Redis, replay exato, política fora de ordem e bloqueios reais por conta/IP com expiração. Consulte o [guia](docs/runbooks/resiliencia-ponta-a-ponta-local.md), a [ADR-027](docs/adr/027-resiliencia-ponta-a-ponta.md) e as [evidências 7D2](docs/evidencias/etapa-07d2.md).

A 8A1 cria a área de ingestão `iam_audit.event_log` particionada por mês, recibos globais de deduplicação e manutenção opcional das partições futuras. Preserva a auditoria legada e a Outbox, sem conectar consumidor nesta entrega. Consulte o [guia](docs/runbooks/auditoria-particionada-local.md), a [ADR-028](docs/adr/028-base-auditoria-particionada.md) e as [evidências 8A1](docs/evidencias/etapa-08a1.md).

A 8A2 acrescenta logins/pools dedicados para ingestão e manutenção, provisionamento administrativo separado do Flyway e timeouts. O login de ingestão recebe INSERT/SELECT no log; manutenção só executa uma função limitada. Consulte o [guia](docs/runbooks/identidades-auditoria-local.md), a [ADR-029](docs/adr/029-identidades-auditoria.md) e as [evidências 8A2](docs/evidencias/etapa-08a2.md).

A 8B conecta o consumidor opcional de `audit.persist` ao pool dedicado: JDBC batch atômico, ACK após commit, isolamento de conflitos para DLQ e recuperação por requeue/reconexão. Use `-EnableAuditConsumer`. Consulte o [guia](docs/runbooks/consumidor-auditoria-local.md), a [ADR-030](docs/adr/030-consumidor-auditoria-em-lote.md) e as [evidências 8B](docs/evidencias/etapa-08b.md).

A 8C acrescenta consulta administrativa opcional por cursor estável e filtros exatos, usando transações somente leitura no pool de auditoria. Use `-EnableAuditQuery` com bootstrap administrativo. A V15 adiciona índices para ator/correlação. Consulte o [guia](docs/runbooks/consulta-auditoria-local.md), a [ADR-031](docs/adr/031-consulta-auditoria-por-cursor.md) e as [evidências 8C](docs/evidencias/etapa-08c.md).

A 8D1 acrescenta arquivamento administrativo de meses antigos no mesmo PostgreSQL, com catálogo imutável, preservação de recibos e tratamento de replays/eventos tardios. Não remove dados nem reduz disco. O script opera em modo de plano por padrão; consulte o [guia](docs/runbooks/arquivamento-auditoria-local.md), a [ADR-032](docs/adr/032-arquivamento-mensal-auditoria.md) e as [evidências 8D1](docs/evidencias/etapa-08d1.md).

A 8D2 adiciona um benchmark optativo de ingestão JDBC e consultas com milhões de eventos sintéticos, planos SQL e contagem de SELECTs por página. Execute `Measure-Audit.ps1`; consulte o [guia](docs/runbooks/medicao-auditoria-local.md), a [ADR-033](docs/adr/033-medicao-auditoria-em-escala.md) e as [evidências 8D2](docs/evidencias/etapa-08d2.md).

A 9A1 adiciona leitura opcional de segredos KV v2 antes da criação das conexões do IAM, com AppRole, snapshot validado e revogação do token de leitura. Use `Start-IamCore.ps1 -EnableVaultKv`. Consulte o [guia](docs/runbooks/vault-kv-local.md), a [ADR-034](docs/adr/034-bootstrap-kv-vault.md) e as [evidências 9A1](docs/evidencias/etapa-09a1.md).

A 9A2a prepara o grupo PostgreSQL `securegate_iam_runtime`, sem login e com concessões explícitas, e valida o IAM com uma identidade de execução sem propriedade das tabelas. A V17 permite ao trigger registrar histórico da Outbox sem INSERT direto pelo runtime. O startup padrão ainda usa a conta proprietária. Consulte o [guia](docs/runbooks/permissoes-runtime-local.md), a [ADR-035](docs/adr/035-permissoes-postgresql-runtime.md) e as [evidências](docs/evidencias/etapa-09a2a.md).

A 9A2b integra credenciais PostgreSQL temporárias do Vault com `-EnableVaultDatabase`, AppRole/KV de execução sem senha proprietária e migração em processo separado (`Migrate-Database.ps1`). A 9A2c acrescenta renovação e troca de gerações. Consulte o [guia](docs/runbooks/vault-database-local.md), a [ADR-036](docs/adr/036-credenciais-dinamicas-postgresql.md) e as [evidências](docs/evidencias/etapa-09a2b.md).

A 9A2c renova token e lease do pool, valida novas credenciais antes da troca e drena a geração anterior com prazo limitado. Em indisponibilidade, fecha sessões ao vencer o prazo confirmado e pode reconstruir o pool após recuperação do Vault, sem reiniciar. Consulte o [guia de renovação](docs/runbooks/vault-database-renovacao-local.md), a [ADR-037](docs/adr/037-renovacao-e-troca-de-pools.md) e as [evidências](docs/evidencias/etapa-09a2c.md).

A 9B1 adiciona pedidos de acesso privilegiado com escopo dedicado, MFA obrigatório, justificativa, expiração e revogação pelo proprietário, com auditoria/Outbox atômicas. A 9B2 abaixo acrescenta a emissão de credenciais Vault. Consulte o [guia](docs/runbooks/pedidos-privilegiados-local.md), a [ADR-038](docs/adr/038-pedidos-de-acesso-privilegiado.md) e as [evidências](docs/evidencias/etapa-09b1.md).

A 9B2 emite uma única credencial de diagnóstico por pedido, com escopo/MFA e reautorização, Vault/AppRole e emissor separados, prazo SQL limitado e revogação recuperável. Senhas não são persistidas; sessões abertas podem ultrapassar o TTL durante indisponibilidade do Vault. Consulte o [guia](docs/runbooks/credenciais-pam-local.md), a [ADR-039](docs/adr/039-emissao-pam-no-vault.md) e as [evidências](docs/evidencias/etapa-09b2.md).

A 9C1 acrescenta auditoria de expiração lógica de pedidos/credenciais, correlação de origem e recusas autenticadas com códigos fixos. Expiração observada não é confirmação de revogação pelo Vault. Consulte o [guia](docs/runbooks/auditoria-pam-local.md), a [ADR-040](docs/adr/040-auditoria-operacional-pam.md) e as [evidências](docs/evidencias/etapa-09c1.md).

## Decisões aprovadas

- Java 21, Spring Boot e Spring Authorization Server.
- Maven com Wrapper e build multimódulo.
- Vault Transit para assinatura JWT desde a etapa de autenticação.
- PostgreSQL, Redis e RabbitMQ para dados, estado distribuído e eventos.

## Documentação

- [Roteiro de implementação e acompanhamento](docs/PLANO-DE-IMPLEMENTACAO.md)
- [ADR-001 — Stack e organização](docs/adr/001-stack-e-organizacao.md)
- [ADR-002 — Assinatura JWT com Vault Transit](docs/adr/002-assinatura-jwt-vault-transit.md)
- [Infraestrutura local: execução, credenciais e diagnóstico](infra/README.md)
- [ADR-003 — Baseline da infraestrutura](docs/adr/003-infraestrutura-local.md)
- [Execução e diagnóstico do iam-core](docs/runbooks/iam-core-local.md)
- [Evidências da etapa 2](docs/evidencias/etapa-02.md)
- [Provisionamento administrativo local](docs/runbooks/provisionamento-local.md)
- [ADR-004 — Identity, Access e bootstrap administrativo](docs/adr/004-identity-access-bootstrap.md)
- [ADR-005 — Integração Transit e publicação de chaves](docs/adr/005-transit-e-jwks.md)
- [ADR-006 — Authorization Server JDBC e Client Credentials](docs/adr/006-authorization-server-client-credentials.md)
- [ADR-007 — Login, PKCE e consentimento](docs/adr/007-login-pkce-e-consentimento.md)
- [ADR-008 — Refresh, introspecção e revogação](docs/adr/008-refresh-rotacao-e-revogacao.md)
- [ADR-009 — OIDC, discovery e userinfo](docs/adr/009-oidc-discovery-e-userinfo.md)
- [ADR-010 — Políticas e tabela de decisão](docs/adr/010-politicas-e-tabela-de-decisao.md)
- [ADR-011 — ABAC e risco determinístico](docs/adr/011-abac-e-risco-deterministico.md)
- [ADR-012 — Coleta confiável de sinais de risco](docs/adr/012-coleta-confiavel-de-risco.md)
- [ADR-013 — Base TOTP e cifra de segredos pelo Transit](docs/adr/013-base-totp-e-cifra-transit.md)
- [ADR-014 — Cadastro MFA, recuperação e prova de step-up](docs/adr/014-cadastro-mfa-recuperacao-e-step-up.md)
- [ADR-015 — Políticas na emissão e renovação de tokens](docs/adr/015-politicas-na-emissao-de-tokens.md)
- [ADR-016 — Desafio MFA na navegação da autorização](docs/adr/016-desafio-mfa-na-autorizacao.md)

O roteiro deriva do documento **SecureGate IAM – Documentação e Plano de Implementação**, versão 0.1, outubro de 2026, disponível em PDF na pasta local do projeto.

## Próximo passo

A **9C2a** acrescenta V21, retry persistido de limpeza com intervalos de 2–60 s, avanço de outros itens durante falhas e métricas agregadas das pendências/resultados incertos. Consulte o [guia de recuperação PAM](docs/runbooks/recuperacao-pam-local.md), a [ADR-041](docs/adr/041-recuperacao-da-limpeza-pam.md) e as [evidências 9C2a](docs/evidencias/etapa-09c2a.md). O [passo a passo](docs/GUIA-DE-IMPLEMENTACAO.md) documenta a divisão da 9C2.

A **9C2b** acrescenta V22, retry persistido e isolamento por item no observador de pedidos, métricas de pendências e teste com reinício real da JVM do IAM após falha de auditoria. Consulte a [ADR-042](docs/adr/042-recuperacao-do-observador-pam.md), o [runbook](docs/runbooks/recuperacao-pam-local.md) e as [evidências 9C2b](docs/evidencias/etapa-09c2b.md). Prazo e correlação permanecem inalterados; o teste de reinício cobre o observador com fixture SQL e emissão desabilitada.

A **9C3** valida com JARs reais a jornada OAuth/MFA → pedido → credencial Vault → uso PostgreSQL → revogação/expiração → Outbox/RabbitMQ → ingestão dedicada → consulta central. Inclui pausa do Vault/reinício do IAM, indisponibilidade do broker e isolamento das identidades, além da correção do bootstrap OAuth para execução com migração externa/Flyway desabilitado. Consulte o [guia integrado](docs/runbooks/jornada-pam-integrada-local.md), a [ADR-043](docs/adr/043-aceite-integrado-pam.md) e as [evidências 9C3](docs/evidencias/etapa-09c3.md). V22 continua sendo a migração atual.

A **9A3** permite iniciar o enforcer com `Start-PolicyEnforcer.ps1 -EnableConsumer -EnableVaultKv`. Obtém credenciais Redis/RabbitMQ por AppRole e KV v2, valida o snapshot completo e revoga o token antes de iniciar os clientes; falhas impedem o startup sem fallback local. Consulte [operação](docs/runbooks/enforcer-vault-kv-local.md), [ADR-044](docs/adr/044-bootstrap-kv-enforcer.md) e [evidências 9A3](docs/evidencias/etapa-09a3.md). Não há refresh em execução nem nova migração.

A **9D** consolida o [aceite técnico local da etapa 9](docs/runbooks/aceite-etapa9.md), com matriz de controles, testes e riscos residuais; consulte as [evidências 9D](docs/evidencias/etapa-09d.md). Usa os 778 testes do build da 9A3, sem nova execução funcional. A 9A3 está versionada em `6d08c2a`.

A **10A1** inicia a etapa de carga com massa sintética de clientes, plano JMeter Client Credentials, sucesso/invalid_client separados e runner que falha quando as asserções não passam. O smoke opt-in usa IAM real e serviços descartáveis; resultados não incluem corpos/tokens/cabeçalhos. Consultar [operação](load-tests/jmeter/README.md), [ADR-045](docs/adr/045-base-de-carga-jmeter.md) e [evidências](docs/evidencias/etapa-10a1.md). Não comprova capacidade ou metas de latência.

A **10A2a** acrescenta massa humana sintética e jornada PKCE com cookies, CSRF, state, consentimento, identidade/TTL e recusa de replay. O smoke opt-in prova também que verifier incorreto resulta em HTTP 400 e falha do runner. Consultar [operação humana](load-tests/jmeter/HUMAN-PKCE.md), [ADR-046](docs/adr/046-carga-humana-pkce.md) e [evidências](docs/evidencias/etapa-10a2a.md). A duração é agregada por jornada, sem comprovar capacidade ou metas.

A **10A2b1** acrescenta plano de falhas de login com senha incorreta e usuário inexistente, mensagem genérica/CSRF/cookies verificados e sessão anônima confirmada. O smoke opt-in prova dois fatos na Outbox e detecta autenticação inesperada ao injetar senha correta. Consultar [operação](load-tests/jmeter/LOGIN-FAILURES.md), [ADR-047](docs/adr/047-carga-de-falhas-de-login.md) e [evidências](docs/evidencias/etapa-10a2b1.md). Não comprova tempo constante ou capacidade.

A **10A2b2** acrescenta acesso Bearer ao recurso de conta, recusa de token inválido, revogação e recusa posterior antes da expiração. Reutiliza PKCE/massa humana com opções explícitas; teste isolado confere publicação/projeção e detecta revogação omitida. Consultar [operação](load-tests/jmeter/RESOURCE-REVOCATION.md), [ADR-048](docs/adr/048-carga-de-recurso-e-revogacao.md) e [evidências](docs/evidencias/etapa-10a2b2.md). O ledger local também recusa tokens: este smoke não mede SLA distribuído.

A **10A2b3** acrescenta produção de fatos pela API, consulta central por correlação/tipo, duas páginas com cursor real e recusa sem credencial. O smoke compara envelopes com a Outbox e detecta correlação inexistente. Consultar [operação](load-tests/jmeter/AUDIT.md), [ADR-049](docs/adr/049-carga-de-auditoria-central.md) e [evidências](docs/evidencias/etapa-10a2b3.md). Os planos 10A cobrem os cenários funcionais; medições de capacidade/SLA permanecem pendentes.

A **10B1** acrescenta exportação Prometheus opt-in no IAM, token próprio de leitura por arquivo e histogramas HTTP opcionais. Preserva saúde e administração, sem expor métricas publicamente. Consultar [inventário](docs/OBSERVABILIDADE.md), [operação](docs/runbooks/metricas-prometheus-local.md), [ADR-050](docs/adr/050-exportacao-prometheus-protegida.md) e [evidências](docs/evidencias/etapa-10b1.md).

A **10B2a** acrescenta Compose local de Prometheus/Grafana, token de scrape por arquivo, senha própria do Grafana e dashboard IAM de 12 painéis. O smoke usa IAM real, valida expressões/datasource, acesso anônimo negado e falha/recuperação de credencial. Consultar [operação](docs/runbooks/coleta-iam-local.md), [Compose](infra/observability/README.md), [ADR-051](docs/adr/051-coleta-e-dashboard-local-do-iam.md) e [evidências](docs/evidencias/etapa-10b2a.md).

A **10B2b1** acrescenta listener opcional de métricas do enforcer, token separado, job Prometheus e dashboard de seis painéis. Preserva Spring non-web e modo padrão sem listener. O smoke comprova evento real/projeção/ACK, coleta autenticada e falha de credencial isolada do IAM. Consultar [operação](docs/runbooks/metricas-enforcer-local.md), [ADR-052](docs/adr/052-metricas-opcionais-do-enforcer.md) e [evidências](docs/evidencias/etapa-10b2b1.md).

A **10B2b2a** acrescenta coleta opcional RabbitMQ com plugin nativo, porta loopback, métricas agregadas, script de migração com backup e dashboard de seis painéis. O smoke usa mensagens AMQP reais e verifica ready/unacked/ACK e pausa/recuperação do scrape. Consultar [operação](docs/runbooks/metricas-rabbitmq-local.md), [ADR-053](docs/adr/053-coleta-opcional-rabbitmq.md) e [evidências](docs/evidencias/etapa-10b2b2a.md).

A **10B2b2b** acrescenta login de monitoração PostgreSQL, exporter opcional com senha por arquivo e imagem fixada por digest, coleta de estatísticas e dashboard de seis painéis. Smoke verifica ACLs reais e falha de credencial SQL com scrape HTTP disponível, seguida de recuperação. Consultar [operação](docs/runbooks/metricas-postgres-local.md), [ADR-054](docs/adr/054-coleta-opcional-postgresql.md) e [evidências](docs/evidencias/etapa-10b2b2b.md).

A **10B2b2c** acrescenta ACL própria Redis sem acesso a chaves/valores, exporter opcional com senha por arquivo e imagem fixada por digest, coleta agregada e dashboard de seis painéis. Smoke verifica recusas de dados/admin, reinício com ACL/AOF preservados e falha/recuperação de senha. Consultar [operação](docs/runbooks/metricas-redis-local.md), [ADR-055](docs/adr/055-coleta-opcional-redis.md) e [evidências](docs/evidencias/etapa-10b2b2c.md).

A **10B2b2d** acrescenta listener Vault dev autenticado, política mínima, token de 1h por arquivo/rotação, coleta e dashboard de seis painéis. Aplicar o overlay recria Vault dev e perde estado em memória; esta entrega só o aplicou em testes descartáveis. Consultar [operação/matriz de correlação](docs/runbooks/metricas-vault-local.md), [ADR-056](docs/adr/056-coleta-autenticada-vault-dev.md) e [evidências](docs/evidencias/etapa-10b2b2d.md).

A **10B3a** implementa perfil opcional structured-logs no IAM/enforcer: JSON com campos controlados, catálogo finito e hash de correlação, excluindo mensagens, argumentos, exceções e MDC livre. Consultar [operação](docs/runbooks/logs-estruturados-local.md), [ADR-057](docs/adr/057-logs-operacionais-json-restritos.md) e [evidências](docs/evidencias/etapa-10b3a.md).

A **10B3b1** acrescenta tracing HTTP entrante opcional (perfil http-tracing), W3C restrito, amostragem local, spans sem URL/payload/erros livres e IDs no JSON IAM. Sem envio externo. Consultar [operação](docs/runbooks/tracing-http-local.md), [ADR-058](docs/adr/058-tracing-http-entrante-restrito.md) e [evidências](docs/evidencias/etapa-10b3b1.md).

A **10B3b2** instrumenta os envios JDK runtime para Vault (Transit/MFA, banco dinâmico e PAM), com contexto restrito à origem/rotas configuradas e spans sem segredos. KV pré-Spring permanece fora do tracing. Consultar [operação](docs/runbooks/tracing-http-local.md), [ADR-059](docs/adr/059-tracing-clientes-http-vault.md) e [evidências](docs/evidencias/etapa-10b3b2.md).

A **10B3c1** acrescenta V23: contexto W3C opcional e imutável persistido na mesma transação do outbox, fora do payload v1, preservado em claims/retries. **Migrar antes do novo binário**, inclusive com tracing desligado. Consultar [operação](docs/runbooks/tracing-outbox-local.md), [ADR-060](docs/adr/060-contexto-duravel-no-outbox.md) e [evidências](docs/evidencias/etapa-10b3c1.md).

A **10B3c2** adiciona span PRODUCER por tentativa de publicação, com parent durável ou raiz explícita, e somente traceparent adicional nos headers AMQP. Mantém payload v1 e regras de confirm/retry/lease. Consultar [operação](docs/runbooks/tracing-outbox-local.md), [ADR-061](docs/adr/061-tracing-publicacao-amqp.md) e [evidências](docs/evidencias/etapa-10b3c2.md).

A **10B3c3a** acrescenta tracing opcional nas duas filas do enforcer: contexto AMQP restrito, span CONSUMER por entrega e limpeza de escopo/MDC, preservando ACK/retry/DLQ e efeitos Redis. O perfil consumer-tracing é independente da ativação dos workers. Consultar [operação](docs/runbooks/tracing-consumidores-local.md), [ADR-062](docs/adr/062-tracing-consumo-enforcer.md) e [evidências](docs/evidencias/etapa-10b3c3a.md).

A **10B3c3b** instrumenta a auditoria IAM em lote com raiz CONSUMER e links aos produtores distintos, preservando transação única, commit antes do ACK e isolamento/retry. Reutiliza http-tracing; sem novo schema/exporter. Consultar [operação](docs/runbooks/tracing-consumidores-local.md), [ADR-063](docs/adr/063-tracing-auditoria-em-lote.md) e [evidências](docs/evidencias/etapa-10b3c3b.md).

Próxima proposta: **10B4 — fechamento da observabilidade**, incluindo backend/exportação de tracing e coleta conjunta, antes de **10C — medição**. A instrumentação de consumo 10B3c3 está implementada; aceite global 10B ainda aberto. Trabalho local em `feat/outbox-trace-context`, sobre `103553e`, incluída neste commit; push pendente; build completo aprovado com 845 testes e gates de cobertura/arquitetura, com ressalva de shutdown nas evidências. Publicação/CI e aceite de desempenho permanecem pendentes.

A v1 tem foco em API REST. Gateway é opcional; federação externa, WebAuthn e painel administrativo completo ficam para evoluções posteriores.

## Build local

Pré-requisitos: JDK 21, `JAVA_HOME` apontando para esse JDK, Docker Engine disponível e internet na primeira execução. O Wrapper baixa Maven 3.9.11. Testcontainers inicia PostgreSQL, Vault, RabbitMQ e Redis temporários; não é necessário subir o Compose para executar `verify`.

Windows PowerShell:

```powershell
.\mvnw.cmd -version
.\mvnw.cmd -B -ntp clean verify
```

Linux/macOS:

```sh
./mvnw -version
./mvnw -B -ntp clean verify
```

O build valida JDK/Maven, verifica formatação, executa testes unitários e de integração e gera os JARs. `iam-core/target/iam-core-0.1.0-SNAPSHOT.jar` e `policy-enforcer/target/policy-enforcer-0.1.0-SNAPSHOT.jar` são executáveis Spring Boot. O enforcer não possui servidor HTTP e seu consumidor é opcional. Os testes de integração falham se Docker não estiver disponível, sem serem ignorados silenciosamente.

JaCoCo exige pelo menos 80% de cobertura de linhas por pacote de domínio/aplicação de Identity, Access, Authorization e Policy. O relatório HTML fica em `iam-core/target/site/jacoco/index.html`, e a CI inclui os relatórios de cobertura no artefato de testes.

Para executar apenas testes unitários/arquitetura sem iniciar containers, use `./mvnw test` ou `.\mvnw.cmd test`. Isso não substitui o aceite completo de `verify`. Relatórios ficam em `target/surefire-reports/` e `target/failsafe-reports/` dos módulos.

Para corrigir formatação Java, execute `.\mvnw.cmd spotless:apply` no Windows ou `./mvnw spotless:apply` no Linux/macOS. O `verify` apenas verifica a formatação, sem alterar o código.

| Módulo | Responsabilidade atual |
| --- | --- |
| `libs/security-events` | Biblioteca Java sem Spring, com validação inicial dos tipos de evento |
| `iam-core` | Identity/Access, políticas e simulação administrativa, auditoria básica, OAuth/OIDC com refresh, Transit/JWKS, Actuator e Flyway |
| `policy-enforcer` | Consumidores de revogação e bloqueio/política com projeções Redis, consultadas opcionalmente pelo recurso do IAM |
| `integration-tests` | Testes ponta a ponta dos JARs com infraestrutura descartável e medição da propagação |

O contrato inicial `SecurityEventType` aceita segmentos minúsculos separados por ponto, até 128 caracteres, e rejeita curingas de binding (`*` e `#`). O envelope v1 e a persistência transacional estão na 6A1; os seis produtores foram integrados na 6A2; a 6B publica no broker quando habilitada; consumidores continuam na etapa 7. Relatórios de teste ficam em `libs/security-events/target/surefire-reports/`.

## Infraestrutura local

Com Docker Desktop em execução, na raiz do repositório:

```powershell
.\infra\scripts\Initialize-Local.ps1
docker compose --env-file .env -f infra/docker-compose.yml up -d --wait --wait-timeout 180
.\infra\scripts\Test-Local.ps1
```

PostgreSQL fica em `127.0.0.1:15432`, Redis em `127.0.0.1:6379`, RabbitMQ em `127.0.0.1:5672` com painel na porta `15672`, e Vault na porta `8200`. Credenciais são geradas em `.env` e `infra/.local/`, ignorados pelo Git.

Vault usa modo dev e perde chaves ao reiniciar. Consulte o [guia de infraestrutura](infra/README.md) para reexecutar o bootstrap, operar a stack e entender os limites do ambiente local.

## Executar o iam-core

Após o build e a subida da infraestrutura:

```powershell
.\infra\scripts\Start-IamCore.ps1
```

O script lê o `.env` local e transmite a senha por variável de ambiente ao processo Java, sem gravá-la no código ou passá-la na linha de comando. A aplicação escuta em `127.0.0.1:8080`; `-Port 18080` permite trocar a porta. As migrações serão aplicadas ao banco local na primeira inicialização.

Endpoints públicos: `GET /actuator/health`, `/actuator/health/liveness` e `/actuator/health/readiness`. Por padrão, as rotas administrativas permanecem bloqueadas. Para provisionar no ambiente local, iniciar explicitamente com:

```powershell
.\infra\scripts\Start-IamCore.ps1 -EnableBootstrap
```

As rotas `/api/admin/**` e a leitura de `/actuator/metrics/**` exigem o token gerado em `.env`. Consulte o [guia de provisionamento](docs/runbooks/provisionamento-local.md) para exemplos, controle de versão e atribuições. A sessão de usuário e os JWTs OAuth não concedem acesso administrativo.

Para habilitar `GET /oauth2/jwks`, use `Start-IamCore.ps1 -EnableTransit`. Essa opção lê os arquivos AppRole de `infra/.local/` e é independente do bootstrap administrativo. Operação, rotação e diagnóstico estão no [guia Transit](docs/runbooks/transit-local.md).

Para emitir tokens máquina a máquina, use `Start-IamCore.ps1 -EnableAuthorization -EnableBootstrap`, cadastre um cliente confidencial e solicite `client_credentials` com Basic Auth em `POST /oauth2/token`, conforme o [guia Client Credentials](docs/runbooks/oauth-client-credentials-local.md). Authorization ativa Transit automaticamente; bootstrap só é necessário para administrar. O segredo é entregue uma vez e fica armazenado como hash Argon2id.

Os mesmos parâmetros habilitam o fluxo de navegador. Cadastre o cliente em `/api/admin/oauth-clients/browser`, provisione um usuário ACTIVE e siga o [guia de login e PKCE](docs/runbooks/oauth-pkce-local.md). A sessão local dura 15 minutos por inatividade, o consentimento pendente cinco minutos e o código dois minutos. `refreshTokens: true` habilita a renovação descrita no [guia de refresh](docs/runbooks/oauth-refresh-local.md). Bloqueios e mudanças de versão da identidade impedem usar sessões/códigos/refresh antigos. Introspecção consulta o estado atual; recursos que verificam apenas assinatura JWT dependem da futura propagação de revogação ou da expiração do token.

Para autenticação OIDC, cadastre o cliente web com `oidc: true`, que adiciona `openid` automaticamente, e siga o [guia OIDC](docs/runbooks/oidc-local.md). Discovery fica em `/.well-known/openid-configuration`; userinfo exige Bearer access token do fluxo OIDC. O ID token tem audiência do cliente, e o access token, da API. O cliente deve validar assinatura, issuer, audiência, expiração e nonce antes de concluir seu login.

Para administrar políticas, basta `-EnableBootstrap`; OAuth/Transit são independentes. Use `/api/admin/policies` e `POST /api/admin/policies/evaluate`, conforme o [guia de políticas](docs/runbooks/politicas-local.md). `/api/admin/policies/simulate` testa cenários sintéticos com `simulation: true`, conforme o [guia ABAC/risco](docs/runbooks/abac-risco-local.md). A API protegida `GET /api/resources/account` exige Bearer de usuário, audiência configurada e política `account:self` / `read`; consulte o [guia de recursos](docs/runbooks/recursos-local.md), a [ADR-017](docs/adr/017-autorizacao-por-operacao-nos-recursos.md) e as [evidências 5D3](docs/evidencias/etapa-05d3.md). A política e sua auditoria são gravadas na mesma transação; edições exigem a versão atual.

Para coletar contexto real, habilite Authorization e faça login no navegador. A administração lista contextos em `/api/admin/risk/users/{userId}/contexts`; informe o `contextId` correspondente em evaluate. Redes, sensibilidade e aprovação de navegador ficam sob `/api/admin/risk/**`, conforme o [guia de coleta](docs/runbooks/coleta-risco-local.md). Headers de encaminhamento não definem a origem observada. A 5D usa esses sinais na navegação, emissão/refresh e recurso local; evaluate/simulate permanecem ferramentas administrativas.

## Arquivos locais

A pasta `.firecrawl/` contém cache local de pesquisa e extração de documentos e é ignorada pelo Git.

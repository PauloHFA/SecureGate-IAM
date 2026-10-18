# ADR-006 — Authorization Server JDBC e Client Credentials

- Status: aceito para a entrega 4B.
- Data: 18/10/2026.
- Complementa [ADR-005](005-transit-e-jwks.md).

## Escopo e decisão

Integrar Spring Authorization Server 1.5.8, gerenciado pelo Spring Boot, com seus repositórios JDBC de clientes, autorizações e consentimentos. A entrega 4B publica o token endpoint para clientes confidenciais máquina a máquina. Authorization Code, sessão de usuário, PKCE, consentimento interativo e OIDC continuam nas entregas 4C/4D.

A ativação é explícita: `iam.authorization.enabled=true` exige o encoder Transit da 4A. As auto-configurações de Authorization Server/JWT do Boot são excluídas para evitar clientes em memória ou assinatura local por configuração implícita. Sem o encoder, a composição falha na inicialização; não há fallback criptográfico.

## Persistência e transação

A migração V3 segue os três schemas SQL fornecidos no artefato oficial 1.5.8, preservando os nomes e colunas esperados. Conforme orientação do próprio framework para PostgreSQL, `blob` vira `text` e `timestamp` vira `timestamptz`. Adicionamos unicidade de `client_id`, referências entre tabelas e índice por cliente. As tabelas ficam em `iam_authorization`; as migrações antigas permanecem intactas.

O SQL JDBC do framework usa nomes sem schema. `AuthorizationJdbcDataSource` delimita o schema durante o uso de cada conexão e o restaura antes de liberá-la. Um `TransactionAwareDataSourceProxy` participa da transação do datasource principal, permitindo que cadastro e auditoria confirmem ou revertam juntos. O adaptador não cria outro pool nem reescreve SQL/serialização do framework. Os testes verificam rollback e restauração do schema.

Esta opção mantém o modelo JDBC padrão antes de qualquer customização JPA. As limitações de desempenho do serviço JDBC são conhecidas e serão medidas nas etapas de carga; não declaramos metas de throughput cumpridas. O formato padrão persiste tokens e metadados recuperáveis no banco: banco e backups devem ser tratados como dados sensíveis. Controle de privilégios, retenção/limpeza e proteção operacional serão aprofundados nas etapas de hardening.

## Cadastro e segredo

`POST /api/admin/oauth-clients` exige o bootstrap administrativo da etapa 3. Recebe `clientId`, nome, audiência e até dez escopos. O domínio rejeita IDs inválidos, escopos vazios, curingas e escopos de login `openid`/`offline_access`. O segredo aleatório tem 256 bits, codificação Base64URL e só aparece na resposta de criação, com `Cache-Control: no-store`. O resultado tem `toString` mascarado.

No banco fica `{argon2}` seguido do hash Argon2id com os parâmetros já usados pela porta de senhas de Identity. A autenticação do framework lê os parâmetros do hash por seu `DelegatingPasswordEncoder`. A consulta administrativa retorna somente metadados. O evento `oauth.client.created` registra cliente/audiência/ator/correlação, sem segredo ou hash, na mesma transação. Cadastros duplicados retornam conflito; a restrição única protege também a corrida entre requisições.

Rotação de segredo, desativação e listagem administrativa de clientes não integram esta primeira API de cadastro/consulta. O segredo não é recuperável; não existe cliente ou senha de exemplo embutido na aplicação.

## Contrato do fluxo

| Item | Decisão 4B |
| --- | --- |
| Endpoint | `POST /oauth2/token`, formulário URL encoded |
| Grant cadastrado | `client_credentials` |
| Autenticação | `client_secret_basic` exclusivamente |
| Escopos | Solicitação explícita, não vazia e subconjunto do cadastro |
| Audiência | Uma audiência aprovada no cadastro; não deriva da entrada do token request |
| Validade | 5 minutos |
| Assinatura | RS256, chave privada no Transit, `kid` versionado |
| Claims | `iss`, `sub` e `client_id` do cliente, `aud`, `iat`, `nbf`, `exp`, `jti`, `scope` |
| Sessão/usuário | Sem claims `sid` ou `roles`; não há usuário autenticado neste fluxo |
| Tokens adicionais | Sem refresh token ou ID token |

O issuer é configurado explicitamente, com HTTPS remoto ou HTTP de loopback no desenvolvimento, sem credenciais, query, fragmento ou path. Não é inferido de headers recebidos. O script local ajusta issuer e porta juntos.

A cadeia OAuth é stateless e cobre somente `/oauth2/token`. JWKS continua com a cadeia da 4A; as rotas administrativas continuam com bootstrap. Um JWT máquina a máquina não concede acesso administrativo. Authorize, introspecção, revogação, userinfo e discovery permanecem bloqueados até as próximas entregas. Persistir consentimentos nesta entrega apenas prepara o armazenamento; não oferece fluxo de consentimento ao usuário.

## Falhas

Falhas esperadas do token endpoint usam JSON OAuth com somente `error`, preservando `X-Correlation-ID` e desabilitando cache. Credencial inválida retorna 401 com challenge Basic; grant inválido, escopo inválido e requisições inválidas usam os códigos do framework. A falha do encoder Transit vira `temporarily_unavailable`, HTTP 503, com `Retry-After: 5`. O framework não salva autorização quando a geração falha.

Erros do cadastro/consulta administrativos mantêm Problem Details. Não usamos o handler MVC para tratar falhas dos filtros OAuth. Outros erros operacionais inesperados, como indisponibilidade do banco, ainda dependem do tratamento geral de erro do servidor; não são apresentados como falha de credenciais.

## Referências e validação

- [Modelo de configuração oficial](https://docs.spring.io/spring-authorization-server/reference/configuration-model.html).
- [Componentes JDBC e geração de tokens](https://docs.spring.io/spring-authorization-server/reference/core-model-components.html).
- [Fontes do artefato 1.5.8, incluindo schemas SQL](https://repo.maven.apache.org/maven2/org/springframework/security/spring-security-oauth2-authorization-server/1.5.8/spring-security-oauth2-authorization-server-1.5.8-sources.jar).
- [Execução local](../runbooks/oauth-client-credentials-local.md).

Os testes integram HTTP real, PostgreSQL e Vault efêmeros. Verificam hash/segredo, claims/assinatura via JWKS, escopos, grants, persistência por novas instâncias JDBC, consentimento armazenado, proteção administrativa, rollback e falha sem emissão com Vault indisponível. Recursos das próximas entregas permanecem explicitamente fora do aceite 4B.

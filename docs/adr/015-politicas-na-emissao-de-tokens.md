# ADR-015 — Políticas na emissão e renovação de tokens de usuário

- Status: aceita para a entrega 5D1
- Data: 2026-10-18

## Contexto

A 5A/5B fornece decisões de política/risco e a 5C fornece prova MFA. A emissão ainda concedia todo o escopo aprovado sem consultar essas decisões. A 5D será dividida: **5D1**, emissão e refresh de tokens de usuário; **5D2**, desafio MFA integrado à navegação de autorização; **5D3**, aplicação a recursos, coordenada com o enforcer da etapa 7. A etapa 5 permanece aberta.

## Decisão

Aplicar políticas obrigatoriamente aos grants `authorization_code` e `refresh_token` no endpoint de token, depois de o Spring validar cliente, grant, PKCE e escopos. Sem política correspondente ou com DENY, não emitir tokens. Não há opção de ignorar políticas para esses grants nem política ALLOW automática no banco da aplicação. Os testes antigos passam a cadastrar permissões explícitas por cliente em suas fixtures.

O alvo é **`resource = oauth:<client_id>`**, **`action = issue`**, tanto no código quanto na renovação. O ID vem do cliente registrado/autenticado. A audiência continua configurada no cliente e não é substituída por esse alvo de política.

`PolicyDecisions` expõe somente avaliação real por porta entre os contextos. `TokenAccessService` verifica identidade atual, avalia com papéis atuais e coleta de risco usando o sid persistido no grant. Não recebe roles, score, MFA ou contexto sintético do cliente. Sinais de rede/dispositivo pertencem ao contexto do navegador, não ao endereço do backend que troca o código por tokens. Evidência antiga/fechada fica desconhecida segundo a 5B2; indisponibilidade não concede acesso.

### Escopos e validade

O resultado é intersectado novamente com os escopos já autorizados pelo provider. Políticas não acrescentam escopos registrados ou consentidos. A validade do access token é o menor limite entre o cliente e a política (até os 300 segundos atuais). A cópia de configuração usada na geração não altera o cadastro do cliente.

Uma decisão é calculada por execução de grant e reutilizada para access/refresh/ID token dessa execução. O estado temporário fica restrito à thread síncrona do provider e é removido em `finally`. Nunca é persistido na sessão do navegador. Escopos/TTL são aplicados ao contexto do gerador; os escopos do modelo persistido e da resposta OAuth também são ajustados dentro da mesma transação. O claim `scope` usa uma lista serializável aprovada pelo mapper JDBC do Spring, sem ampliar a allowlist de tipos.

Em OIDC, `profile`/`email` removidos da decisão não geram claims de nome/e-mail no ID token e não autorizam sua leitura via userinfo. Se `openid` pertence ao conjunto efetivo a ser emitido, uma política não pode removê-lo silenciosamente: negar o grant. ID token continua com audiência do cliente e validade própria de cinco minutos; o limite da política é de access token. Não introduzir claims de MFA ou autorização de API no ID token.

Refresh reavalia a política e pode restringir o grant persistido. Escopos removidos não reaparecem por omissão de `scope` nem por relaxamento posterior da política; exigir um novo fluxo de consentimento para recuperá-los. A janela absoluta e a rotação da família permanecem sob o ledger. A política não aumenta o prazo do refresh token.

### MFA

Quando exigido, consultar `MfaProofs.current` usando usuário, versão e **sid original do grant**. Prova de outra sessão, expirada ou invalidada não serve. Além do limite da política, o access token não ultrapassa o prazo dessa prova; prazo insuficiente para emissão é rejeitado.

Nesta entrega, o usuário comprova o fator manualmente em `/mfa`, na sessão usada na autorização. O endpoint de token não redireciona para uma página e não inicia desafio. Uma rejeição não consome código/refresh ainda válido; é possível comprovar o fator e repetir a troca dentro do prazo, ou reiniciar o fluxo. Se o sid original já foi encerrado, iniciar nova autorização. A navegação automática de desafio/retorno fica na 5D2.

### Transações, erros e auditoria

O adaptador roda dentro das transações existentes de resgate de código e rotação de refresh. Após gerar tokens, harmoniza o modelo persistido/resposta e grava `oauth.policy.applied`, com IDs/versões de políticas, score, exigência MFA, escopos finais e tipo do grant de origem. O evento não inclui código, token, seed, fator ou credenciais.

Uma falha de política/normalização/auditoria marca rollback da emissão. A invalidação por replay de código mantém seu comportamento de commit; esse caso não é tratado como falha de política. Refresh mantém rollback nas rejeições e revogação em replay detectado. Assim, falha depois de assinar um token não o entrega nem deixa consumo parcial no banco.

Negação e MFA insuficiente retornam `invalid_grant` genérico. Indisponibilidade retorna `temporarily_unavailable`/503, com retry conforme handler existente. Não propagar motivos internos, detalhes de regra, corpos de exceção ou credenciais. Logs operacionais registram apenas tipo e localização da falha. O código ou refresh pode ser tentado novamente enquanto for válido; uma emissão bem-sucedida permanece de uso único.

## Limites

Esta entrega não bloqueia a verificação de senha em `/login`, nem impede a criação do consentimento/código antes da avaliação final. **A barreira é a entrega de tokens de usuário.** Client Credentials continua fora desta avaliação por identidade humana; política para identidades de serviço precisa de contrato próprio. A proteção das rotas administrativas continua bootstrap, sem mudança para Bearer de usuário.

Não revoga JWTs existentes ao alterar política, encerrar sessão ou expirar a prova depois de sua emissão. Recursos continuam precisando de validação de JWT, audiência e escopos; propagação de revogação e avaliação por operação pertencem ao enforcer/5D3. Decisões observam o estado disponível na emissão, sem prometer snapshot global de todas as alterações concorrentes de política, papéis e sinais. Não se declara a etapa 5 concluída.

## Referências

- [Provider de código SAS 1.5.8](https://github.com/spring-projects/spring-authorization-server/blob/1.5.8/oauth2-authorization-server/src/main/java/org/springframework/security/oauth2/server/authorization/authentication/OAuth2AuthorizationCodeAuthenticationProvider.java).
- [Provider de refresh SAS 1.5.8](https://github.com/spring-projects/spring-authorization-server/blob/1.5.8/oauth2-authorization-server/src/main/java/org/springframework/security/oauth2/server/authorization/authentication/OAuth2RefreshTokenAuthenticationProvider.java).
- [Gerador JWT SAS 1.5.8](https://github.com/spring-projects/spring-authorization-server/blob/1.5.8/oauth2-authorization-server/src/main/java/org/springframework/security/oauth2/server/authorization/token/JwtGenerator.java).
- [Guia local](../runbooks/politicas-tokens-local.md), [evidências](../evidencias/etapa-05d1.md) e [roteiro](../PLANO-DE-IMPLEMENTACAO.md).

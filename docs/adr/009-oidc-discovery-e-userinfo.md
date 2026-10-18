# ADR-009 — OIDC, discovery e userinfo

- Data: 18/10/2026
- Estado: aceito para a entrega 4D2

## Contexto

O servidor já oferece login, Authorization Code com PKCE, access tokens Transit e refresh rotativo. OIDC adiciona autenticação para aplicações clientes: um ID token precisa identificar o destinatário correto e o userinfo deve respeitar o consentimento e o estado atual da identidade.

## Decisão

Habilitar os componentes OIDC do Spring Authorization Server, mantendo as cadeias separadas para navegador e endpoints stateless. Cadastro web aceita `oidc: true`, por padrão `false`. Essa opção adiciona `openid` aos escopos registrados. O campo administrativo `scopes` continua contendo os escopos da aplicação, podendo incluir `profile` e `email`; não deve incluir `openid`, adicionado automaticamente. Clientes existentes e Client Credentials preservam seu comportamento.

O fluxo continua restrito a clientes confidenciais, Basic Auth e Code com PKCE S256, redirect exato, state e consentimento protegido. Pedidos com `openid` exigem nonce aleatório de 16–128 caracteres do conjunto `[A-Za-z0-9._~-]`. O cliente deve verificar esse nonce no ID token inicial. `openid` é parte necessária da identificação; a tela explica essa finalidade e permite negar o fluxo inteiro.

ID token usa RS256 pelo mesmo Vault Transit, com `kid`, issuer fixo e TTL de cinco minutos. Sua audiência é o client ID e seu `azp` identifica o cliente; não recebe audiência de API, `roles`, `scope` ou `client_id` do access token. `sub` é o UUID da identidade, `sid` é o identificador opaco da sessão e `auth_time` vem do instante de login, persistido como texto ISO-8601 nos metadados. A renovação preserva `sub`, `sid` e `auth_time`, sem repetir o nonce inicial.

`profile` libera somente `name`; `email` libera somente `email`. Nenhuma propriedade `email_verified` é afirmada, pois ainda não há verificação de propriedade de endereço. ID token e userinfo usam os escopos efetivos da emissão atual, inclusive após redução de escopo no refresh.

`GET /.well-known/openid-configuration` e `GET /.well-known/oauth-authorization-server` são públicos somente com Authorization habilitado. Metadados usam o issuer configurado, sem derivá-lo de Host/forwarded headers. Anunciam apenas os três grants disponíveis, Basic Auth, S256, algoritmo RS256 e endpoints implementados. Remover anúncios padrão de Device Code, Token Exchange, mTLS, DPoP, logout OIDC e registro dinâmico, que não estão habilitados nesta entrega.

`/userinfo` usa o provider oficial, Bearer JWT e um decoder baseado nas chaves públicas retidas pelo adaptador Transit, sem HTTP do servidor para si mesmo. A verificação exige RS256, issuer e validade temporal; o provider também exige o access token atual e ativo no JDBC, escopo `openid` e ID token associado. Um mapper consulta Identity pela porta `IdentityProfiles` e exige conta ACTIVE e versão correspondente antes de divulgar claims. Assim, ID token, token adulterado, revogado, expirado, substituído na rotação ou de identidade alterada não dá acesso a userinfo. A revogação e a auditoria continuam seguindo a 4D1.

## Limites

Suporte delimitado ao fluxo documentado; não se declara certificação OpenID. Não há clientes públicos/SPA, Implicit, Hybrid, JAR, Request URI, parâmetro `claims`, `max_age`, `offline_access`, registro dinâmico nem RP-Initiated Logout. Parâmetros `request`, `request_uri`, `claims` e `max_age` são rejeitados. Logout local permanece no formulário CSRF da 4C. Sessões são locais; Redis e propagação de revogação para outros recursos ficam na etapa 7.

O consumidor deve validar assinatura via JWKS, algoritmo, issuer, audiência/client ID, expiração e nonce inicial com biblioteca OIDC apropriada. ID token não substitui access token em APIs. Nome/e-mail presentes nos tokens tornam banco, backups e logs igualmente sensíveis; não imprimir respostas completas. Não há nova migração ou chave privada local.

## Referências

- [Spring Authorization Server — endpoints e customizações oficiais](https://docs.spring.io/spring-authorization-server/reference/protocol-endpoints.html)
- [Spring Authorization Server — customizar userinfo](https://docs.spring.io/spring-authorization-server/reference/guides/how-to-userinfo.html)
- Fontes oficiais da versão 1.5.8, especialmente `JwtGenerator`, `OidcUserInfoAuthenticationProvider` e `OidcProviderConfigurationEndpointFilter`, consultados no sources JAR publicado no Maven Central.
- [ADR-007](007-login-pkce-e-consentimento.md), [ADR-008](008-refresh-rotacao-e-revogacao.md) e [guia OIDC](../runbooks/oidc-local.md).

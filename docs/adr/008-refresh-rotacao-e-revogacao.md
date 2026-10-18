# ADR-008 — Refresh com rotação, introspecção e revogação

- Data: 18/10/2026
- Estado: aceito para a entrega 4D1

## Contexto

A 4C emite access tokens de cinco minutos para clientes web confidenciais. A renovação precisa preservar o consentimento, verificar a identidade atual e impedir que um refresh token interceptado seja reutilizado silenciosamente. OIDC será entregue separadamente na 4D2.

## Decisão

O cadastro de cliente web aceita `refreshTokens: true`; o padrão é `false`, inclusive para clientes existentes. Client Credentials não recebe refresh. O consentimento informa a possibilidade de renovar por até 30 minutos. Não há suporte a `offline_access` nesta entrega.

Cada renovação troca o refresh token por um novo. A família tem prazo absoluto de 30 minutos a partir da primeira emissão; rotação não estende esse prazo. O access token continua com TTL de cinco minutos. O servidor revalida estado/versão da identidade, recalcula papéis e preserva `sub`, `sid`, audiência e o limite dos escopos autorizados. O cliente pode pedir um subconjunto dos escopos; isso não altera permanentemente o consentimento original.

A migração V4 adiciona famílias e histórico de hashes SHA-256. O serviço JDBC padrão mantém o refresh atual no registro de autorização; o histórico adicional guarda apenas hashes. Banco e backups continuam contendo credenciais sensíveis e exigem proteção operacional.

Uma transação PostgreSQL bloqueia a família antes de consultar novamente o histórico, consumir o token e emitir seu sucessor. O consumo e a emissão são confirmados juntos. Falha de escopo ou assinatura faz rollback, permitindo tentar novamente o token ainda válido. Reuso de um token consumido revoga a família e seus tokens atuais; a revogação é confirmada mesmo quando a resposta é `invalid_grant`. Duas renovações concorrentes do mesmo token produzem uma emissão e uma detecção de reuso que invalida essa emissão. O cliente deve serializar renovações.

`POST /oauth2/introspect` e `POST /oauth2/revoke` exigem Basic Auth do cliente proprietário. Introspecção de token desconhecido, de outro cliente, antigo, expirado ou inválido retorna somente `active: false`. Revogação de token desconhecido/de outro cliente retorna sucesso sem alteração. Para famílias, revogar access token ou qualquer refresh conhecido, inclusive histórico, invalida a família inteira. Para Client Credentials, invalida o token apresentado. Repetir revogação não duplica a auditoria. Eventos `oauth.refresh.reused` e `oauth.authorization.revoked` não contêm tokens.

## Consequências e limites

A introspecção reflete o estado no servidor. Verificação offline de assinatura JWT pode continuar aceitando um token revogado até sua expiração; propagação para recursos protegidos e Redis pertencem à etapa 7. Logout do navegador encerra apenas sua sessão local e não revoga automaticamente a família. Os 30 minutos de renovação são independentes do timeout de sessão de 15 minutos.

Não há limpeza automática do histórico nesta entrega; retenção deverá preservar a detecção de reuso enquanto houver tokens da família utilizáveis. Discovery, ID token, userinfo e clientes públicos permanecem pendentes. Não houve nova dependência ou alteração nas credenciais locais.

## Referências

- [ADR-006 — Authorization Server JDBC](006-authorization-server-client-credentials.md)
- [ADR-007 — Login e PKCE](007-login-pkce-e-consentimento.md)
- [Spring Authorization Server — componentes principais](https://docs.spring.io/spring-authorization-server/reference/core-model-components.html)
- [Guia de operação e validação](../runbooks/oauth-refresh-local.md)

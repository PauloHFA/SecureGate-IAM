# ADR-017 — Autorização por operação nos recursos

- Data: 2026-10-19
- Estado: implementada localmente; publicação/revisão pendentes
- Entrega: 5D3

## Decisão

Adicionar uma API Bearer stateless no `iam-core`, inicialmente `GET /api/resources/account`. A operação é `ACCOUNT_READ`, com alvo `account:self`, ação `read` e escopo obrigatório `api:read`. O servidor fixa esses valores; parâmetros do usuário não selecionam operação, identidade, contexto ou sinal de MFA. A resposta contém somente `sub`, escopos efetivos e `expires_at`, sem dados de outro usuário.

O filtro padrão de Bearer do Spring Security usa um introspector local. Embora a integração do framework se chame `opaqueToken`, o valor recebido continua sendo um JWT RS256: `LocalResourceTokens` verifica assinatura Transit, emissor, audiência e validade, e consulta a autorização persistida pelo valor exato do access token. Não há HTTP de introspecção para o próprio servidor. A audiência é `iam.resources.audience`, padrão `securegate-api`, e precisa corresponder à cadastrada no cliente OAuth.

`ResourceTokens` entrega um snapshot confiável com usuário, versão, sid, jti, escopos, emissão e expiração. O adaptador aceita somente grants de usuário Authorization Code, access token ativo e família não revogada. ID tokens, refresh tokens, Client Credentials, tokens substituídos no refresh e valores desconhecidos não criam esse snapshot. Usuário/versão e sid vêm da autorização persistida e devem coincidir com o JWT quando aplicável. Papéis presentes no JWT não são autoridade para a decisão atual.

`ResourceAccessService` verifica a versão ativa da identidade e chama `PolicyDecisions` em cada operação. O motor consulta papéis e risco reais com o sid de origem; sinais ausentes/expirados conservam o tratamento conservador da 5B2. Não se interpreta o endereço da chamada da API como nova observação do navegador de origem. Não há cache de decisão ou permissão reutilizável entre requisições.

Sem política correspondente, DENY. Os escopos são a interseção do grant com a decisão atual e precisam manter `api:read`. O prazo é o menor entre expiração do token e `iat + TTL` da política atual: reduzir TTL pode rejeitar imediatamente um token antigo. Se MFA for exigido, somente `MfaProofs.current` para usuário/versão/sid originais serve de prova; o prazo também fica limitado pela prova. A API não redireciona para `/mfa` e não usa o cookie de sessão. O cliente precisa conduzir a verificação na sessão original e repetir o acesso.

Um acesso permitido exige registrar `resource.access.allowed` antes de devolver dados, com alvo, ação, ator, correlação, escopos, IDs/versões das políticas, risco, exigência MFA e prazo. Token e códigos MFA não são registrados. Falha nessa auditoria impede a resposta de sucesso. A auditoria não torna a leitura e as mudanças concorrentes de autorização uma única transação serializável: a decisão vale no ponto da verificação.

## Contrato HTTP

| Resultado | Resposta |
| --- | --- |
| Credencial ausente, inválida, revogada ou identidade desatualizada | 401 `invalid_token` |
| DENY, escopo obrigatório removido ou TTL atual ultrapassado | 403 `insufficient_scope` |
| Prova MFA original ausente/expirada | 403 `mfa_required` |
| Falha em consulta de estado, política, prova ou auditoria | 503 `temporarily_unavailable`, `Retry-After: 5` |

Respostas de autenticação e sucesso têm `Cache-Control: no-store`. Falhas de verificação criptográfica não revelam detalhes e retornam 401, inclusive quando as chaves públicas não podem ser usadas. Não existe fallback que aceite apenas o JWT. Rotas e métodos não mapeados permanecem negados.

## Integração futura com o enforcer

Na etapa 7, o enforcer poderá compor validações adicionais atrás de `ResourceTokens`: bloqueio/revogação por usuário, versão, jti e sid, com expiração limitada ao token. O adaptador distribuído deverá preservar assinatura/emissor/audiência e vínculo do grant, diferenciar indisponibilidade de ausência de credencial e nunca transformar falha do Redis em permissão. Eventos precisam de idempotência e ordenação por versão conforme o plano das etapas 6/7.

Esse contrato é interno Java, não um endpoint remoto que aceite sinais do consumidor. Novas operações precisam de mapeamento explícito de método/rota para alvo/ação/escopo e de verificações de propriedade quando houver IDs de objetos. A operação de demonstração é somente sobre a própria identidade. Recursos externos continuam sem propagação distribuída; a meta de revogação em menos de dois segundos ainda será medida na etapa 7.

## Consequências

A 5D3 permite verificar os quatro efeitos nos recursos locais, usando as mesmas políticas da emissão sem conceder acesso implícito. Há uma consulta online ao banco, políticas e eventual prova/auditoria por chamada; não se assume o desempenho de uma verificação JWT offline. Logout remove a prova MFA, mas não revoga automaticamente todos os access tokens que não exigem MFA. Revogação explícita, refresh e alterações de identidade têm os efeitos documentados no estado local.

Sem novas migrações, dependências persistentes, sessão distribuída ou alteração de credenciais. Ver [guia](../runbooks/recursos-local.md), [evidências](../evidencias/etapa-05d3.md) e [plano](../PLANO-DE-IMPLEMENTACAO.md).

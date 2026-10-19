# ADR-016 — Desafio MFA na navegação da autorização

- Status: aceita para a entrega 5D2
- Data: 2026-10-19

## Contexto

A 5D1 impede a entrega de tokens quando falta prova MFA, mas exigia que o usuário visitasse `/mfa` manualmente. A 5D2 acrescenta orientação interativa antes do consentimento e nova checagem ao aprová-lo. A emissão/renovação continua reavaliando todas as condições pela 5D1.

## Decisão

Após validar os parâmetros da autorização e identificar a sessão, consultar o mesmo serviço de acesso usando usuário, versão, sid e cliente registrados no servidor. A orientação tem três resultados: READY, MFA_REQUIRED ou DENIED. Não concede consentimento nem emite tokens.

Em GET `/oauth2/authorize`, o validador estrito roda antes da orientação. Requisições anônimas continuam no fluxo normal de login do Spring Authorization Server (SAS). Para uma identidade de navegador autenticada:

- READY: SAS continua o fluxo normal de consentimento/autorização.
- MFA_REQUIRED: guardar um retorno pendente e redirecionar localmente para `/mfa`.
- DENIED: retornar `access_denied` à URI de callback já validada, preservando o state original.
- Dependência indisponível: resposta local 503 sanitizada, sem fallback ou callback não validado.

No POST de aprovação do consentimento, conferir novamente política/prova antes do provider gravar a aprovação ou criar código. Usar a requisição original persistida e vinculada a usuário/sid, não reconstruí-la com dados livres do POST. Se precisar de MFA, retirar o consentimento pendente antigo e iniciar um retorno para uma nova passagem pelo fluxo. Campos fora dos escopos originalmente pedidos continuam sendo rejeitados pelo SAS. Recusa do consentimento passa pelo SAS sem obrigar MFA.

O filtro de navegação roda depois das verificações CSRF e antes do filtro de autorização SAS. Intercepta somente sua interrupção interna de navegação; erros do protocolo continuam no handler original do SAS. Não usar APIs internas de validação de prompt do Spring. A validação estrita limita prompt OIDC aos valores padronizados `none`, `login`, `consent` e `select_account`, com até 64 caracteres; `none` não pode ser combinado com outro valor. Isso não acrescenta interfaces de seleção de conta ou reautenticação forçada.

### Retorno pendente

Manter no máximo um desafio pendente por sessão local. O registro contém ticket aleatório UUID, usuário, versão, sid, nome do cliente, parâmetros normalizados e expiração absoluta de cinco minutos. Um desafio novo substitui o anterior; consultar a tela não prolonga esse prazo.

Guardar somente os campos suportados: `response_type=code`, `client_id`, `redirect_uri`, `scope`, `state`, `code_challenge`, `code_challenge_method=S256`, e, quando presentes, nonce/prompt. Eles vêm da requisição validada pelo servidor. Não guardar URL arbitrária de retorno, `JSESSIONID`, segredo, código TOTP ou recuperação. A representação de diagnóstico do registro oculta os parâmetros.

`AuthorizationContinuation.current` só apresenta o contexto ao usuário/versão/sid originais. O botão de retorno aparece apenas com prova MFA autoritativa vigente. `POST /oauth2/continue` exige autenticação, CSRF, ticket correto, identidade atual, vínculo e prazo válidos e prova MFA. Consome o ticket uma única vez e responde 303 para **`/oauth2/authorize`**, com os parâmetros normalizados e codificados. Não aceita `return_to` ou outro destino do navegador.

A retomada volta ao início da validação SAS e da avaliação de política. Portanto, alteração de cadastro, escopos, política, prova ou contexto durante o desafio não é ignorada. Logout e login bem-sucedido limpam o retorno; mudança de identidade invalida a sessão conforme o filtro existente. Outro navegador não pode usar o ticket, mesmo autenticado com a mesma conta.

### MFA e consentimento

Cadastro TOTP, verificação e recuperação continuam sob os controles da 5C2. A resposta da ativação mantém os dez códigos de recuperação visíveis e oferece **Continuar autorização**; não redirecionar antes de o usuário poder guardá-los. Verificação de fator ativo também oferece o botão. A tela `/mfa` pode recuperar esse botão enquanto o retorno e a prova forem válidos.

MFA não aprova os escopos. A retomada aplica as regras normais do SAS: apresentar consentimento quando necessário, ou respeitar um consentimento válido que o usuário já concedeu anteriormente. Não copiar automaticamente a aprovação do POST interrompido. State, nonce e PKCE originais permanecem vinculados ao fluxo e o endpoint de token continua reavaliando políticas/limites/prova.

Em OIDC com `prompt=none`, não criar desafio nem mostrar MFA: falta de fator retorna `interaction_required`; falta de login mantém `login_required` do SAS. Requisições inválidas continuam rejeitadas antes de criar retorno. Ausência de política/DENY não é tratada como MFA insuficiente.

## Consequências e limites

A navegação de Authorization Code passa a orientar o usuário para o fator necessário. Refresh continua sendo uma chamada do backend sem redirecionamento interativo; na falta de prova, é necessário usar a sessão original ou iniciar novo fluxo, conforme a 5D1. Se o cadastro exigir login recente e a sessão for antiga, sair/entrar e reiniciar a autorização; um sid novo não herda o retorno anterior.

O armazenamento de sessão é local à instância. Retornos não sobrevivem a reinício e não há promessa de sessão distribuída. Uma segunda aba pode substituir o desafio da primeira. Proteção de recursos por operação e propagação de revogação permanecem na 5D3/enforcer; não se declara a etapa 5 encerrada. Não há migração nova nesta entrega.

Referências: [provider de autorização SAS 1.5.8](https://github.com/spring-projects/spring-authorization-server/blob/1.5.8/oauth2-authorization-server/src/main/java/org/springframework/security/oauth2/server/authorization/authentication/OAuth2AuthorizationCodeRequestAuthenticationProvider.java), [provider de consentimento](https://github.com/spring-projects/spring-authorization-server/blob/1.5.8/oauth2-authorization-server/src/main/java/org/springframework/security/oauth2/server/authorization/authentication/OAuth2AuthorizationConsentAuthenticationProvider.java), [guia](../runbooks/desafio-mfa-local.md), [evidências](../evidencias/etapa-05d2.md) e [roteiro](../PLANO-DE-IMPLEMENTACAO.md).

# ADR-007 — Login, Authorization Code com PKCE e consentimento

- Status: aceito para a entrega 4C.
- Data: 18/10/2026.
- Complementa [ADR-006](006-authorization-server-client-credentials.md).

## Fluxo e clientes

A entrega 4C habilita usuários já provisionados em Identity a entrar com e-mail/senha e autorizar clientes web confidenciais. `POST /api/admin/oauth-clients/browser` exige o bootstrap administrativo e gera um segredo de 256 bits entregue uma única vez. Esses clientes usam exclusivamente `authorization_code` e `client_secret_basic`, com PKCE obrigatório e consentimento habilitado. Não recebem Client Credentials nem refresh automaticamente. Clientes públicos/SPA e OIDC não fazem parte desta entrega.

O domínio reaproveita as validações de ID, nome, audiência e escopos. Aceita até três redirects de até 250 caracteres, com HTTPS remoto ou HTTP em `localhost`/`127.0.0.1`, path obrigatório e sem query, fragmento ou userinfo. As URIs são persistidas literalmente. O validador de autorização exige correspondência exata de toda a string, incluindo porta e path; não aplica a exceção de porta dinâmica de loopback do validador padrão.

A requisição inicial deve ser GET e conter escopos não vazios, `state` de 16–128 caracteres `[A-Za-z0-9._~-]`, `code_challenge_method=S256` e desafio Base64URL de 43 caracteres. O servidor valida isso antes de exigir login. O cliente deve gerar seu próprio state aleatório, conferir o state no callback e guardar o verifier fora do navegador de terceiros. O framework valida o verifier na troca do código.

## Identidade e autenticação

`JdbcLoginUsers` consulta e-mail normalizado e credencial Argon2id do próprio contexto Identity. O autenticador Dao do Spring usa Argon2id com os parâmetros já existentes. A checagem do estado da conta ocorre após verificar a senha; usuários inexistentes passam pelo hash fictício do provider. A interface mostra a mesma mensagem para senha incorreta, usuário desconhecido, bloqueado ou desativado. Não há afirmação de tempo de resposta constante entre esses cenários.

`LoginPrincipal` carrega somente UUID, versão e estado como dados serializáveis. O hash é transitório, ignorado pelo Jackson e apagado pelo mecanismo `CredentialsContainer`. A classe possui suporte Jackson explícito restrito a esse tipo, sem habilitar tipagem arbitrária. Os detalhes HTTP da autenticação são removidos antes do uso no fluxo OAuth. A persistência JDBC mantém o snapshot da identidade para a troca do código.

`IdentityLookup.isCurrent` verifica conta ACTIVE e a versão do usuário. A sessão é invalidada quando o estado/versão muda; a emissão a partir do código também verifica o snapshot. Alterações de senha e perfil incrementam essa versão, portanto exigem novo login. Essa escolha conservadora evita reutilizar uma autenticação anterior à alteração de credenciais.

## Cadeias de segurança e sessão

- O token endpoint da 4B permanece stateless e exige autenticação do cliente.
- JWKS continua com sua cadeia pública da 4A.
- A cadeia de navegador cobre `/login`, `/logout`, `/oauth2/authorize` e `/oauth2/consent`.
- A administração permanece protegida pelo bootstrap; sessão de usuário ou JWT OAuth não concedem acesso administrativo.

As páginas mínimas de login e consentimento são servidas pela aplicação, em português, sem scripts ou assets externos. Valores dinâmicos são escapados. As respostas usam `no-store`, CSP restrita, bloqueio de framing e `Referrer-Policy: no-referrer`.

A sessão HTTP é local à instância, expira por inatividade em 15 minutos e usa cookie HttpOnly, SameSite=Lax e somente transporte por cookie. HTTPS nativo do container determina o atributo Secure; a terminação TLS/proxy precisa da configuração operacional correta antes de produção. Spring troca o identificador da sessão ao autenticar. O retorno após login usa apenas path/query de uma requisição salva para `/oauth2/authorize`, sem reproduzir autoridade/host recebidos.

Login e logout exigem CSRF. O Spring Authorization Server exclui seus endpoints da checagem CSRF padrão; por isso um segundo `CsrfFilter`, com nome próprio para não ser ignorado pelo controle de execução única, protege explicitamente o POST de consentimento em `/oauth2/authorize`. Ele usa o mesmo repositório de tokens de sessão. Esse POST também verifica o vínculo entre state pendente, cliente, usuário e sessão.

O dispatcher interno ERROR é permitido para que erros de protocolo não sejam convertidos em 403 pela cadeia genérica. Isso não libera requisições externas para `/error`.

## Consentimento, código e JWT

O framework persiste o pedido e um state interno de consentimento. `BrowserAuthorizations` acrescenta um `sid` UUID opaco, diferente do JSESSIONID, e uma validade de cinco minutos para o consentimento pendente. A expiração é uma string ISO-8601, compatível com a desserialização restrita do repositório JDBC. A página consulta o pedido salvo, sem confiar em escopos ou nomes enviados na query da página.

O consentimento pode autorizar os escopos selecionados ou negar todos. A resposta ao cliente preserva o state original. Consentimentos concedidos são persistidos pelo framework e podem dispensar uma nova tela para os mesmos escopos em autorizações futuras. Um pedido pendente de outra sessão não pode ser exibido/aprovado, mesmo para o mesmo usuário.

O código dura dois minutos. `SerializedCodeRedemption` envolve o provider padrão em uma transação PostgreSQL com `SELECT ... FOR UPDATE` pelo código. Isso torna a leitura/validação/invalidação atômica entre requisições concorrentes. Falhas OAuth do provider são propagadas após confirmar eventuais invalidações de reuso; falhas inesperadas de banco revertem a transação. Uma concorrência só pode produzir uma resposta de emissão bem-sucedida.

O access token dura cinco minutos e continua RS256 via Transit. O sujeito é o UUID do usuário, audiência vem do cadastro do cliente, e os escopos são os consentidos. `roles` agrega nomes de papéis diretos e de grupos atuais, sem duplicação; `sid` vem da autorização vinculada à sessão. A avaliação contextual/RBAC dos escopos ainda pertence à etapa 5: incluir os papéis no JWT não substitui o motor de políticas.

## Limites

Não há ID token, discovery OIDC, userinfo ou refresh nesta entrega. Logout encerra a sessão local do navegador; não promete revogação distribuída de tokens ou de todo código anteriormente emitido. JWTs já emitidos continuam verificáveis até a expiração; as entregas 4D/7 completarão o ciclo de revogação. Redis, rate limiting e eventos de login permanecem nas etapas previstas no roteiro. Não há balanceamento entre instâncias para a sessão local nesta fase.

Não foi necessário alterar os schemas SQL: as tabelas JDBC da V3 já comportam códigos, consentimentos e metadados. Banco e backups continuam sensíveis e sujeitos às medidas de hardening planejadas.

## Referências

- [Spring Authorization Server: configuração de endpoints](https://docs.spring.io/spring-authorization-server/reference/configuration-model.html).
- [Componentes, persistência e geração de tokens](https://docs.spring.io/spring-authorization-server/reference/core-model-components.html).
- [Guia oficial de PKCE](https://docs.spring.io/spring-authorization-server/reference/guides/how-to-pkce.html).
- [Execução local](../runbooks/oauth-pkce-local.md) e [evidências 4C](../evidencias/etapa-04c.md).

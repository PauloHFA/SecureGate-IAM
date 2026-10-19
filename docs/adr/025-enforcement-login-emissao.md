# ADR-025 — Sinais distribuídos no login e emissão

- Estado: aceita para implementação local na 7C2.
- Data: 2026-10-19.
- Complementa a [consulta Redis no recurso](024-consulta-redis-no-recurso.md).

## Escopo

O modo opcional existente (`-EnableResourceEnforcement`) passa a proteger também POST /login, navegação humana de autorização e emissão/refresh de tokens de usuário. O modo desligado conserva a composição anterior. Não há novo segredo, migração, topologia ou consumidor.

`AuthenticationEnforcement` é uma porta da aplicação de autorização. O adaptador Redis existente implementa ambas as portas e conserva GET estrito, timeouts, validação de tipos/valores e ausência de fallback permissivo. Bootstrap injeta a composição ativa ou desabilitada.

## Login

Depois do CSRF e antes da autenticação por senha, um filtro confere somente o IP do par conectado. Bloqueio por IP responde 429 rate_limited para conta conhecida ou desconhecida, sem consulta de identidade/senha e sem registrar uma falha fictícia de credencial. Falha da dependência responde 503, sem revelar detalhes. Forwarded headers continuam sem definir a origem.

Após verificar a senha e o status local pelo provider, o check adicional confere identidade versionada e bloqueio de conta/versão/IP. Negação mantém a falha genérica `/login?error`, como senha/conta recusada, sem criar sessão autenticada. Indisponibilidade é traduzida a falha de serviço e 503; o handler não registra falha de senha nesses casos. Verificação de senha/dummy hash e status local não são substituídos por Redis. GET /login e logout não passam pelo filtro IP de login.

## Emissão, refresh e navegação

A avaliação de acesso a tokens de usuário exige identidade local corrente e consulta sid encerrado, identidade projetada e bloqueio da conta/versão. Depois da decisão local positiva, consulta os watermarks das políticas aplicadas. Negação impede emissão; indisponibilidade não vira permit ou desafio sintético.

No gerador de tokens, antes de obter o permit, consultar a família da autorização persistida e o IP real da requisição. Isso vale para Authorization Code e Refresh Token. Revogação apenas do jti antigo não revoga a família: refresh continua permitido quando as outras verificações passam. Client Credentials permanece fora dos sinais humanos; não se inventa identidade/sid para máquina.

Negação de emissão usa invalid_grant; erro Redis usa temporarily_unavailable/503. As exceções passam pelo mecanismo transacional já existente, preservando código/refresh quando falha a barreira adicional. A política e a prova são reavaliadas na execução; orientação de navegação não aprova a emissão.

Um sinal de risco por sid exige MFA real mesmo se a política local não exigia. Não altera score observado nem amplia escopos/TTL e não afirma fraude. Sem prova válida do usuário/versão/sid original, a navegação orienta o desafio e a emissão é negada. Com prova válida, o vencimento do novo token fica limitado ao da prova, pelo mecanismo existente. Redis nunca fornece prova MFA.

## Operação e limites

O recurso da 7C1 continua protegido. Os contadores `iam.resources.enforcement` agora incluem login_ip, login, session, authorization e risk, além das operações anteriores. Risco usa resultados present/absent; demais consultas usam allowed/denied, e falhas usam unavailable. Contagens de consultas internas podem se sobrepor; não são contagem de logins finais.

O limitador aplica as marcas e janelas de falhas projetadas pela 7B. Não faz reserva/incremento síncrono por tentativa HTTP, não é um limite estrito de QPS e depende da propagação dos eventos para bloquear novas tentativas. IP bloqueado não estende o prazo apenas por receber requisições recusadas. Rate limiting de Client Credentials e controles gerais contra volume ficam fora desta entrega.

Introspecção/userinfo conservam suas verificações locais anteriores; não usam esta nova porta. O recurso ainda consulta banco e política locais, sem promessa de verificador offline. Perda/eviction de Redis não é reconstruída por estas leituras. Timeouts são por comando, não orçamento total da requisição. Medição da cadeia e revisão dos cenários de ponta a ponta continuam na 7D.

Referências: [guia](../runbooks/autenticacao-enforcement-redis-local.md) e [evidências 7C2](../evidencias/etapa-07c2.md).

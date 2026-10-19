# Login e emissão com enforcement Redis — 7C2

## Ativar

Com infraestrutura e JARs atualizados:

```powershell
.\infra\scripts\Start-IamCore.ps1 -EnableBootstrap -EnableOutboxPublisher -EnableResourceEnforcement
```

Em outra janela:

```powershell
.\infra\scripts\Start-PolicyEnforcer.ps1 -EnableConsumer
```

O mesmo modo opcional da [7C1](recurso-enforcement-redis-local.md) agora inclui login e emissão/refresh humanos. O switch ativa Authorization/Transit, usa credenciais existentes por ambiente e não inicia o consumidor automaticamente. Sem ele, a composição anterior permanece. Não limpe Redis/volumes para atualizar.

## Respostas esperadas

| Fluxo / condição | Resposta |
| --- | --- |
| POST /login sem CSRF válido | 403, antes do filtro Redis de login |
| POST /login com IP bloqueado | 429 rate_limited, uniforme para conta conhecida/desconhecida |
| Senha/conta ou check de conta projetada recusados | Redirect genérico /login?error |
| Consulta Redis de login falha | 503 temporarily_unavailable; sem falha fictícia de senha |
| Emissão/refresh com sessão/família/conta/IP/política recusados | OAuth invalid_grant |
| Emissão/refresh com consulta Redis indisponível | 503 temporarily_unavailable |
| Risco por sid sem prova MFA atual da sessão original | Desafio na navegação; emissão/refresh negados |

429/503 de login usam no-store e Retry-After de cinco segundos. A orientação não representa o prazo exato do bloqueio. O provider verifica senha antes do check adicional da conta; não há aprovação a partir de estado Redis ACTIVE ou ausência de chave.

O IP vem da conexão, sem confiar em forwarded headers. Conta conhecida usa userId/versão. Bloqueio administrativo e versões continuam sendo consultados no banco; Redis acrescenta impedimentos. Negação de código/refresh pela barreira adicional não deve consumi-los: corrigida a causa transitória, a tentativa pode ser repetida dentro de sua validade.

## Risco e MFA

O sinal temporário `risk:<hash-do-sid>` exige prova MFA real, mesmo com política local sem MFA. A inscrição/ativação/verificação segue o [guia MFA](mfa-local.md). Prova de outro sid não aprova o grant original. O token emitido com essa exigência tem expiração limitada pela prova, sem aumentar escopos ou TTL local e sem substituir o score local por um número inventado.

O sinal não bloqueia automaticamente a identidade/IP nem comprova fraude. Se expirar, deixa de acrescentar a exigência; a política local pode continuar exigindo MFA. Um sid encerrado permanece negado pela marca terminal, independentemente de possuir prova antiga.

## Limites do rate limiting

O fluxo aplica bloqueios produzidos pelos [contadores 7B](enforcer-bloqueio-politica-local.md). As tentativas recusadas por IP antes da senha não geram outra falha de credencial nem estendem o prazo por si só. Contadores continuam sendo alimentados por eventos reais, com atraso de publicação/consumo. Isso não é um token bucket síncrono nem limite rígido de requisições por segundo.

Client Credentials não recebe identidade/sid/sinais humanos. Introspecção e userinfo permanecem com seus checks locais existentes. A medição ponta a ponta e a revisão desses limites pertencem à próxima 7D.

## Diagnóstico

Conectividade/senha/timeout e validação estrita usam as mesmas configurações da [7C1](recurso-enforcement-redis-local.md), sem novos parâmetros. Redis indisponível impede o fluxo protegido, sem cache permissivo ou fallback de autenticação. Recuperação permite nova tentativa sem reiniciar o IAM. Não apague marcas para contornar falha: altere a origem administrativa ou reconcilie estado com acesso operacional autorizado.

`iam.resources.enforcement` inclui operações login_ip, login, session, authorization, risk e as anteriores token/request/policy. Risk usa present/absent; demais checks, allowed/denied; falhas, unavailable. São consultas internas, com tags limitadas e sem usuário/IP/token. Allowed não comprova autenticação ou emissão final.

Os testes usam Redis/PostgreSQL/Vault descartáveis, projeções compatíveis aplicadas diretamente e prova MFA real. A cadeia completa Outbox → RabbitMQ → enforcer → HTTP e sua latência ainda serão medidas na 7D. Veja [ADR-025](../adr/025-enforcement-login-emissao.md) e [evidências](../evidencias/etapa-07c2.md).

A [7D1](propagacao-ponta-a-ponta-local.md) acrescenta a medição de revogação/logout na cadeia completa. Falhas, replay/reordenação e bloqueios por limiar integrados continuam na 7D2.

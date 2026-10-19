# ADR-026 — Medição da propagação com os serviços empacotados

- Estado: aceita para implementação local na 7D1.
- Data: 2026-10-19.

## Decisão

A 7D é dividida em **7D1**, baseline funcional e medição de revogação/logout, e **7D2**, falhas, duplicação, reordenação e bloqueios por limiar na cadeia completa. O aceite da etapa 7 depende das duas entregas e da revisão dos limites operacionais.

O módulo Maven `integration-tests` roda depois dos dois serviços. As dependências `type=pom`, restritas a teste, estabelecem a ordem do reactor; os serviços são iniciados por `java -jar`, fora do classpath de aplicação do teste. Não há importação de classes internas, substituição de beans, manipulação de grants ou escrita direta nas projeções Redis. O Failsafe executa a suíte no `verify` normal e na CI.

Cada execução usa PostgreSQL, RabbitMQ, Redis e Vault descartáveis. Senhas e tokens administrativos são gerados em memória; AppRole usa arquivos temporários removidos ao terminar. Os processos recebem configuração pelo ambiente, sem credenciais nos argumentos. Não se lê `.env` nem se usa a stack persistente. Encerramento dos processos antecede a parada dos contêineres.

## Medição e correlação

Provisionamento, políticas ALLOW e login/PKCE/consentimento usam HTTP real. A fixture administra uma rede local confiável e sensibilidade PUBLIC para `auth:login`, mantendo o risco abaixo do limiar de suspeita, inclusive durante o aquecimento do histórico. Isso permite testar revogação sem criar prova MFA fictícia ou apagar sinais de risco. O fluxo de risco/MFA continua coberto na 7C2.

Há três amostras sequenciais por alvo: access token sem refresh (`jti`), família após rotação (`authorizationId`) e logout (`sid`). Antes de cada revogação, o token precisa retornar 200 no recurso, a marca terminal precisa estar ausente e a Outbox precisa estar sem pendências. O ambiente medido, portanto, está ocioso e sem backlog deliberado.

O relógio monotônico começa ao receber a confirmação HTTP de revogação/logout. A observação só termina quando **todas** as condições forem verdadeiras:

1. Um único fato transacional da operação é localizado pela correlação enviada na requisição.
2. A Outbox registra publicação confirmada.
3. O enforcer registrou o `eventId` e o efeito terminal no Redis compartilhado.
4. O token anteriormente aceito recebe 401 no recurso HTTP.

Os checks de banco e de Redis são somente leitura. O marcador temporário deve conservar `expiresAt + 60 segundos`, margem do protocolo 7A; sid encerrado continua permanente (`0`, sem TTL inventado). Cada amostra exige duração observada inferior a 2.000 ms e grava CSV sem tokens, senhas ou dados de usuário. Os polls de observação de 25 ms e o custo HTTP tornam a medida um limite superior amostrado, sem identificar o instante exato da escrita Redis.

## Limites

O IAM também mantém barreiras locais de grant/identidade/sessão. Uma resposta 401 isolada não demonstra passagem pela consulta Redis. Exigir evento, publicação, projeção e rejeição evita concluir propagação a partir apenas dessa barreira local, mas não atribui a recusa exclusivamente ao Redis. A integração específica da leitura Redis ao HTTP foi testada separadamente nas 7C1/7C2.

Nove amostras locais não são benchmark, percentil de produção, garantia multi-instância ou prova sob carga. Permanecem pendentes na 7D2: interrupção/recuperação dos componentes na cadeia completa, replay/duplicação, reordenação e bloqueios por limiar. Reconstrução após perda/eviction Redis, sessões compartilhadas e orçamento total por requisição continuam limites operacionais.

Referências: [guia](../runbooks/propagacao-ponta-a-ponta-local.md), [evidências](../evidencias/etapa-07d1.md) e [plano](../PLANO-DE-IMPLEMENTACAO.md).

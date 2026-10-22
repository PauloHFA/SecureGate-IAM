# ADR-038 — Pedidos de acesso privilegiado

- Data: 20/10/2026
- Estado: aceita para a entrega local 9B1

## Escopo e decisão

Dividir a 9B em **9B1**, autorização e registro dos pedidos, e **9B2**, emissão de credenciais Vault para um alvo dedicado. A 9B1 não entrega senha, token Vault ou conexão PostgreSQL. `AUTHORIZED` descreve um pedido aprovado pela política naquele instante; o ID não é uma credencial, não concede acesso ao banco e não substitui nova autorização na futura emissão.

Manter o contexto Authorization e suas portas existentes. V18 adiciona `iam_authorization.privileged_requests`, com usuário, alvo fixo `database:diagnostics`, justificativa, criação, expiração e revogação. Não adicionar concessão automática de papel nem política permissiva no seed. A API opcional usa `iam.pam.enabled`, desabilitada por padrão; habilitar requer Authorization/Transit. O launcher `-EnablePam` ativa essas dependências.

## Autorização

`POST /api/pam/requests`, `GET /api/pam/requests/{id}` e `DELETE /api/pam/requests/{id}` exigem access token de usuário do Authorization Code local, audiência da API, escopo `pam:request`, identidade atual e política corrente para `database:diagnostics` / `request`. Os mecanismos existentes consultam sessão/grant, revogação, papéis e, quando habilitado, estado Redis. Token bootstrap, sessão de navegador isolada e Client Credentials não substituem esse token.

MFA é obrigatório para essas operações mesmo se a política for `ALLOW`, vinculado ao usuário/versão/sid original. As políticas podem impor condições e prazo menor; `DENY` prevalece. Os endpoints não aceitam sujeito, caminho Vault, papel de banco ou ação escolhidos pelo cliente. Consultar/revogar exige a mesma autorização corrente; outro usuário recebe 404 para IDs alheios. Após perda dessa autorização, a expiração permanece limitada; revogação administrativa de credenciais emitidas será tratada na próxima entrega.

## Persistência e prazos

Justificativa de 10 a 500 caracteres após strip, sem caracteres de controle; pedido de TTL inteiro entre 30 e 300 segundos. O prazo efetivo é o menor entre o solicitado e o permit atual, já limitado por idade do token, política e prova MFA. Pode restar menos de 30 segundos. Normalizar criação/expiração a microssegundos antes de persistir, arredondando para baixo para não ampliar o permit no round-trip PostgreSQL.

`EXPIRED` é calculado na leitura quando `expires_at <= agora`; não depende de scheduler nem é prorrogado pela consulta. Revogação usa UPDATE condicional e terminal, sem ampliar o prazo. Repetição de DELETE não gera novo evento. Repetição de POST cria outro pedido: não há garantia de idempotência ou autorização de uso único nesta fase. Retenção/limpeza e auditoria durável de expiração ficam na 9C.

Gravar pedido e evento `pam.request.authorized` na mesma transação com a auditoria/Outbox existentes. Revogação grava `pam.request.revoked` na mesma transação. Falha de auditoria desfaz a alteração. Eventos contêm apenas requestId e expiresAt, além de ator/alvo/correlação do envelope; não contêm justificativa, senha, token ou semente MFA. A justificativa é persistida no banco e não aparece na resposta. Não inserir credenciais nela. Respostas usam `Cache-Control: no-store`.

O perfil runtime recebe SELECT/INSERT e UPDATE somente de `revoked_at`. Não recebe DELETE, UPDATE de justificativa/prazo ou privilégios de diagnóstico no PostgreSQL. Reaplicar o provisionamento após V18; a emissão dinâmica do runtime continua independente do futuro PAM.

## Validação e próximos passos

Testes cobrem autorização HTTP com OAuth/PKCE e MFA reais, rejeição sem escopo/política/prova, isolamento, expiração, revogação idempotente, rollback por falha de auditoria e permissões PostgreSQL restritas. Ver [evidências](../evidencias/etapa-09b1.md) e [guia](../runbooks/pedidos-privilegiados-local.md).

A 9B2 precisa definir permissões do alvo, AppRole/ACL dedicados, entrega única do segredo, consumo concorrente/idempotência do pedido, TTL real do Vault, revogação e recuperação de emissão ambígua. A 9C completa emissão/expiração/revogação e recuperação operacional. Não declarar PAM nem etapa 9 concluídos por esta fundação.

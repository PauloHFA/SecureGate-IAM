# ADR-018 — Envelope de eventos e Outbox transacional

- Data: 2026-10-19
- Estado: implementada localmente; publicação/CI remota/revisão pendentes
- Entrega: 6A1, primeira parte da 6A

## Decisão

Adicionar `SecurityEvent` em `libs/security-events`, sem dependência de Spring ou Jackson em execução. O envelope v1 contém `eventId`, `eventType`, `schemaVersion`, `occurredAt`, `producer`, `correlationId`, `actor`, `context` e `data`. O recurso afetado fica em `context.resource`. As chaves de roteamento continuam usando a validação de `SecurityEventType`.

O módulo empacota o [JSON Schema v1](../../libs/security-events/src/main/resources/contracts/security-event-v1.schema.json). Testes verificam serialização/deserialização JSON, nomes dos campos, horário UTC, versão desconhecida, limites, cópia imutável dos mapas e diagnóstico sem conteúdo dos metadados. O schema é um artefato para consumidores; os testes atuais conferem seus campos obrigatórios e versão, sem executar um motor completo de JSON Schema.

Os mapas têm até 32 entradas cada, chaves alfanuméricas iniciadas por letra e valores textuais não vazios de até 4096 caracteres. Ator tem até 200 caracteres, correlação até 64, produtor até 64 e recurso até 100. Valores com caracteres de controle são recusados. O produtor local é `iam-core`; o horário UTC vem do relógio da aplicação, truncado em microssegundos para coincidir com PostgreSQL. O adaptador força serialização do horário como string ISO-8601, independentemente da opção global de timestamps do Jackson.

Metadados devem conter somente informação necessária ao evento. Não incluir senha, hash de senha, semente TOTP, códigos de recuperação, credenciais Vault ou valor bruto de access/refresh token. A validação de formato não identifica automaticamente segredos no conteúdo; essa responsabilidade permanece no produtor. Os produtores atuais reutilizam os detalhes já selecionados para auditoria.

## Persistência atômica

A V9 cria `iam_audit.outbox_events`, ligada pelo mesmo `eventId` ao registro de auditoria. Guarda tipo, versão, horário, payload JSONB, criação e `published_at` inicialmente nulo. O índice parcial `(created_at,event_id)` cobre pendências. Payloads acima de 64 KiB são recusados no banco. Restrições conferem campos obrigatórios, versão e correspondência dos cabeçalhos com as colunas, inclusive horário; JSON null não contorna essas verificações. Um trigger impede alteração dos campos originais e do payload. Publicação/limpeza terão regras próprias nas entregas seguintes.

`JdbcAuditTrail.record` passa a executar auditoria e `JdbcOutboxEvents.append` em `TransactionTemplate` com propagação REQUIRED e o mesmo DataSource/transaction manager. Quando há transação de negócio, participa dela. Sem transação externa, abre uma para o par auditoria/Outbox. `append` isolado recusa execução sem transação ativa. A FK exige uma auditoria correspondente, mas não substitui o uso desse adaptador para garantir o par.

Falha na serialização, no INSERT de auditoria ou no INSERT da Outbox propaga a exceção e desfaz a transação. Não há gravação após commit, listener em memória ou envio ao RabbitMQ dentro da transação. As operações que já auditam suas mudanças na UnitOfWork passam a persistir o evento junto do estado. Uma requisição só registra permissão de recurso se também persistir sua auditoria/Outbox.

Cada chamada confirmada a `record` cria um novo UUID. Repetir a chamada deliberadamente representa outro registro: não há deduplicação semântica por payload. Repetição da entrega futura deverá reutilizar o UUID e payload armazenados, sem gerar novos IDs. A PK protege duplicação do mesmo `eventId`; a idempotência dos consumidores pertence à etapa 7.

## Escopo e sequência

Esta entrega envolve somente eventos já enviados a `AuditTrail`. Mantém nomes e dados existentes, por exemplo `identity.user.created`, `policy.created`, `oauth.authorization.revoked` e `resource.access.allowed`. Não os renomeia para as seis chaves de integração previstas, pois isso exigirá dados e testes próprios dos produtores.

O envelope suporta `auth.login.suspicious`, `auth.login.failed`, `token.revoked`, `session.terminated`, `policy.changed` e `user.locked`, mas isso não significa que todos esses eventos já sejam emitidos pelos fluxos reais. A **6A2** definirá os dados e ligará esses seis produtores às transações apropriadas, incluindo versões/expiração necessárias ao enforcer. O consumidor não deve interpretar um evento legado como comando de revogação sem esse contrato.

A **6B** implementará publicação com confirmação, retry e mensagens não roteadas. A **6C** definirá retenção, métricas e cenários de recuperação. `published_at` nulo significa pendência, não falha de envio. Não há job de envio nesta entrega e não há promessa de exactly-once ou meta de propagação medida.

Não há backfill: auditorias existentes antes da V9 não geram mensagens retroativas. A migração não altera versões anteriores nem apaga dados. Outbox e auditoria duplicam metadados e aumentam custo de escrita/armazenamento; a operação prolongada exige a retenção da 6C. Mudanças de payload incompatíveis exigem nova versão, e versões desconhecidas devem ser recusadas pelo consumidor sem aplicar efeitos.

Ver [guia local](../runbooks/outbox-local.md), [evidências](../evidencias/etapa-06a1.md) e [plano](../PLANO-DE-IMPLEMENTACAO.md).

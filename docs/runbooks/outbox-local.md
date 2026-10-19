# Envelope e Outbox local — 6A1

Esta entrega persiste eventos junto da auditoria. Ainda não envia mensagens ao RabbitMQ. A [6A2](eventos-seguranca-local.md) integra os seis produtores de segurança. O próximo passo é a 6B, publicação com confirmação e retry.

## Executar

Com Docker disponível, execute o build e inicie o ambiente conforme o [README](../../README.md) e o [guia de provisionamento](provisionamento-local.md):

```powershell
.\mvnw.cmd -B -ntp clean verify
.\infra\scripts\Start-IamCore.ps1 -EnableAuthorization -EnableBootstrap
```

Na primeira inicialização com o novo JAR, Flyway aplica a V9, criando `iam_audit.outbox_events`. Não é necessário limpar banco/volumes, regenerar credenciais ou mudar `.env`. A Outbox também funciona com Authorization desabilitado, para as operações administrativas já auditadas.

Crie ou atualize um usuário/política pelas APIs existentes. Cada nova chamada confirmada a `AuditTrail` gera uma auditoria e um envelope com o mesmo ID e horário. Auditorias anteriores à migração permanecem sem Outbox; não há reenvio retroativo.

## Inspeção somente leitura

Na conexão PostgreSQL local, consulte:

```sql
SELECT o.event_id, o.event_type, o.schema_version, o.occurred_at,
       o.created_at, o.published_at, o.payload->>'correlationId' AS correlation_id,
       o.payload->'context'->>'resource' AS resource
FROM iam_audit.outbox_events o
WHERE o.published_at IS NULL
ORDER BY o.created_at, o.event_id
LIMIT 20;

SELECT a.id, a.event_type, a.occurred_at,
       o.event_id, o.occurred_at AS envelope_time
FROM iam_audit.security_events a
JOIN iam_audit.outbox_events o ON o.event_id = a.id
ORDER BY a.occurred_at DESC, a.id
LIMIT 20;
```

Nesta etapa, `published_at` é sempre nulo para eventos produzidos pela aplicação. Isso é esperado: nenhum publicador foi implementado. Não preencha esse campo manualmente para simular entrega. O payload original é imutável e `eventId` é único. Publicadores futuros precisam usar o conteúdo persistido, inclusive o mesmo ID, nas tentativas de envio.

## Contrato

O [schema v1](../../libs/security-events/src/main/resources/contracts/security-event-v1.schema.json) está empacotado no JAR `security-events`. O payload contém UUID do evento, tipo, versão do envelope, instante UTC, produtor, correlação, ator, contexto e dados. `context.resource` identifica o recurso; a versão da entidade, quando o produtor já a fornece, fica em `data.version` como texto. Esse campo não deve ser confundido com `schemaVersion`, a versão do formato do envelope.

Ator, correlação e dados vêm da auditoria interna; não existe endpoint para o cliente enviar um envelope arbitrário. Senhas, sementes, códigos MFA e tokens brutos não devem aparecer no payload. `SecurityEvent.toString()` omite os metadados, mas serializar/logar o payload inteiro ainda pode revelar informações pessoais ou operacionais; use a consulta resumida acima.

## Falhas e validação

- Falha ao gravar Outbox desfaz auditoria e mudança de estado participantes da mesma transação.
- Rollback externo também remove a auditoria/Outbox; conexões independentes não veem o evento antes do commit.
- Chamar o adaptador da Outbox sem transação é recusado. Escritas SQL diretas não substituem o fluxo transacional da aplicação.
- Versão desconhecida, metadados inválidos ou payload acima de 64 KiB impedem a gravação; não há fallback que descarte o evento e confirme o estado.
- RabbitMQ indisponível não participa desta etapa: nenhum envio ocorre. Testes de confirmação, retry e recuperação do broker serão feitos na 6B/6C.

O build usa PostgreSQL/Vault temporários e verifica contratos JSON, rollback, visibilidade, duplicação de ID, integridade dos cabeçalhos e imutabilidade do payload. Não há alteração de topologia RabbitMQ nesta entrega.

Ver [ADR-018](../adr/018-envelope-e-outbox-transacional.md), [evidências](../evidencias/etapa-06a1.md) e [plano](../PLANO-DE-IMPLEMENTACAO.md).

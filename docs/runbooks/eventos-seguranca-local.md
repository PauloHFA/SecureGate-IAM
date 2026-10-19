# Produtores de eventos de segurança — 6A2

Inicie o novo JAR conforme o [guia da Outbox](outbox-local.md), com Authorization/Transit e bootstrap habilitados para testar todos os fluxos. Flyway aplica a V10 automaticamente. Não limpe volumes nem recrie credenciais. Ainda não há publicação no RabbitMQ.

## Exercitar os produtores

| Ação real | Evento esperado |
| --- | --- |
| Errar a senha no formulário `/login` | `auth.login.failed`, com attemptId e conta tentada quando conhecida |
| Autenticar com contexto classificado em score 70 ou mais | `auth.login.suspicious`, uma vez por sid |
| Criar/editar política pela API administrativa | `policy.changed`, além da auditoria legada |
| Alterar usuário ACTIVE para LOCKED pela API administrativa | `user.locked`, com a nova versão |
| Revogar token próprio em `/oauth2/revoke` | `token.revoked` para jti ou autorização/família |
| Reutilizar refresh já consumido | `token.revoked` por REFRESH_REUSE; OAuth rejeita o grant |
| Fazer POST `/logout` com CSRF ou autenticar novamente | `session.terminated` para o contexto anterior efetivamente fechado |

Use os guias [PKCE](oauth-pkce-local.md), [refresh](oauth-refresh-local.md), [políticas](politicas-local.md) e [provisionamento](provisionamento-local.md). Os eventos não têm endpoint público de ingestão: não envie userId/sid ou flags de risco para tentar produzi-los.

## Inspeção

Na conexão PostgreSQL local:

```sql
SELECT event_id, event_type, occurred_at,
       payload->>'correlationId' AS correlation_id,
       payload->'context'->>'resource' AS resource,
       payload->'data'->>'version' AS entity_version,
       payload->'data'->>'target' AS revocation_target
FROM iam_audit.outbox_events
WHERE event_type IN ('auth.login.failed', 'auth.login.suspicious',
                     'token.revoked', 'session.terminated',
                     'policy.changed', 'user.locked')
ORDER BY occurred_at DESC, event_id
LIMIT 30;
```

Todos permanecem com `published_at` nulo nesta etapa. Não altere esse campo para simular entrega. O [schema dos dados](../../libs/security-events/src/main/resources/contracts/security-integration-event-v1.schema.json) e `SecurityEventData` definem os campos esperados. Eles complementam o envelope da 6A1; eventos legados não devem ser interpretados automaticamente como comandos de bloqueio.

Se usar um motor JSON Schema, registre os dois artefatos pelos seus `$id` URN antes da validação; a referência entre eles é interna, sem download de schema pela rede. Os testes conferem os campos e a referência; não executam um motor completo de JSON Schema.

O evento de tentativa de login não contém e-mail digitado nem senha. O userId, quando presente, identifica a conta tentada e não comprova autenticação. `remoteAddress` representa o par observado, sem confiar em forwarded headers; trate o endereço como dado operacional sujeito a acesso e retenção. Demais credenciais, sementes TOTP e tokens brutos não são publicados.

## Risco e encerramento

O alvo de classificação do login é `auth:login`. Pode ser administrado em `/api/admin/risk/resources`, como os demais recursos do [guia de risco](coleta-risco-local.md). Confiança de dispositivo/rede, falhas recentes e sensibilidade participam do cálculo. Falta de sinais eleva o score; suspeita não significa fraude e não bloqueia automaticamente o usuário. Ler `/mfa` repetidamente não gera outro evento de login para o mesmo sid.

`session.terminated` cobre logout e reautenticação com fechamento real do contexto. Não promete notificação de toda expiração passiva ou falha do processo. Falha de persistência impede confirmar a mudança; repita após recuperar a dependência. No login bem-sucedido, essa falha responde 503 e não deixa sessão autenticada. No logout/revogação, falhas inesperadas de persistência retornam erro do servidor, sem registrar sucesso da operação.

## Consumidores futuros

- Compare `data.version` ao aplicar mudanças de política/identidade; `schemaVersion` versiona o formato e não ordena entidades.
- `user.locked` não deve sobrepor uma identidade ACTIVE de versão posterior. Os eventos legados de identidade continuam persistidos para acompanhar essas mudanças.
- `token.revoked` com alvo ACCESS_TOKEN usa jti; AUTHORIZATION usa autorização/família e prazo. JWTs de usuário novos contêm `authorization_id` estável entre refreshes.
- JWTs antigos sem essa claim não permitem afirmar bloqueio offline por família sem outra verificação confiável.
- Um sid encerrado é terminal. Não use um TTL arbitrário curto: não há expiresAt inventado no evento de sessão. A etapa 7 precisa manter tombstone ou calcular uma janela segura para todos os grants associados.
- Deduplicate por eventId e não marque a entrega concluída antes de aplicar seu efeito. Não deduza uma ordem global pelo relógio.

Confirmação/retry do broker serão a próxima entrega, 6B. Consulte [ADR-019](../adr/019-produtores-de-eventos-de-seguranca.md), [evidências](../evidencias/etapa-06a2.md) e [plano](../PLANO-DE-IMPLEMENTACAO.md).

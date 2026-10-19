# Publicação da Outbox no RabbitMQ — 6B

## Ativar no ambiente local

Com PostgreSQL e RabbitMQ da stack local saudáveis, gere o JAR e inicie:

```powershell
.\mvnw.cmd -B -ntp clean verify
.\infra\scripts\Start-IamCore.ps1 -EnableBootstrap -EnableOutboxPublisher
```

Adicione `-EnableAuthorization` para exercitar login, tokens e MFA. A publicação dos eventos administrativos também funciona sem OAuth/Transit. O script usa RABBITMQ_USER, RABBITMQ_PASSWORD, RABBITMQ_PORT e RABBITMQ_VHOST já presentes no `.env`; não é necessário gerar novas credenciais ou limpar volumes. Flyway aplica a V11 ao iniciar o novo JAR.

O publicador fica desabilitado quando o parâmetro é omitido. Pendências antigas também serão elegíveis ao ativá-lo. A conexão/declaração da topologia acontece sob demanda no primeiro envio; falha de conexão deixa eventos disponíveis para retry. Nenhuma configuração autenticada em `infra/.local/` é empacotada no JAR.

Crie um usuário/política ou siga o [guia dos eventos](eventos-seguranca-local.md). O evento é confirmado primeiro no PostgreSQL; o worker o envia e preenche `published_at` após o ACK do broker, desde que não tenha sido devolvido por falta de rota.

## Inspecionar estado

Na conexão PostgreSQL local:

```sql
SELECT event_id, event_type, attempts, next_attempt_at,
       claim_id, lease_until, last_error_code, published_at
FROM iam_audit.outbox_events
ORDER BY created_at DESC, event_id
LIMIT 30;

SELECT count(*) AS pending,
       count(*) FILTER (WHERE lease_until > statement_timestamp()) AS leased,
       count(*) FILTER (WHERE next_attempt_at > statement_timestamp()) AS waiting_retry
FROM iam_audit.outbox_events
WHERE published_at IS NULL;
```

`last_error_code` é a última falha e pode continuar presente enquanto outra tentativa está em andamento. `published_at` comprova confirmação do broker, não consumo. O [consumidor 7A](enforcer-revogacao-local.md) pode drenar revogações; auditoria e bloqueio/política ainda acumulam até suas entregas.

No painel RabbitMQ local (`http://127.0.0.1:15672`), use as credenciais existentes e selecione o vhost configurado. `audit.persist` recebe todos os tipos. Bloqueio/política recebe as chaves correspondentes, incluindo `auth.login.failed` e `identity.user.*`; revogação recebe `token.revoked` e `session.terminated`.

## Configurações

| Propriedade | Padrão | Limite |
| --- | --- | --- |
| `iam.outbox.publisher.enabled` | false | Ativação explícita |
| `timeout-millis` | 2000 | 100–5000 |
| `lease-seconds` | 30 | 10–300; pelo menos 4 vezes o timeout |
| `batch-size` | 20 | 1–100 |
| `poll-millis` | 1000 | 100–60000 |

As quatro propriedades curtas da tabela usam o mesmo prefixo `iam.outbox.publisher`. Host/porta/vhost/usuário/senha são ligados por `IAM_RABBITMQ_HOST`, `IAM_RABBITMQ_PORT`, `IAM_RABBITMQ_VHOST`, `IAM_RABBITMQ_USERNAME` e `IAM_RABBITMQ_PASSWORD`. O script configura esses valores a partir do ambiente local. Não passe senha em linha de comando.

## Falhas e recuperação

| Código | Comportamento |
| --- | --- |
| UNROUTABLE | RabbitMQ devolveu a mensagem mandatory; não marca publicação |
| NACK | Broker não confirmou aceitação; reagenda |
| CONFIRM_TIMEOUT | Resultado inconclusivo no prazo configurado; reagenda |
| BROKER_UNAVAILABLE | Conexão, topologia ou canal indisponível; reagenda |
| INVALID_EVENT | Envelope/cabeçalhos inválidos; suspende em quarentena na V12 |

O retry usa atrasos exponenciais até cinco minutos para falhas transitórias. Recuperar o broker ou a rota permite nova tentativa com o mesmo eventId e payload. O publicador não descarta eventos após um limite de falhas. `INVALID_EVENT` suspende as tentativas automáticas na V12. Não modifique o payload imutável nem preencha `published_at` para ocultar um incidente; siga os procedimentos de quarentena/liberação da [6C](operacao-outbox-local.md).

Se o processo cair após o broker aceitar a mensagem e antes da confirmação no banco, outro worker poderá enviá-la novamente após o lease. Consumidores precisam de idempotência por eventId. ACK também não comprova que todas as filas pretendidas receberam o evento quando algum binding foi removido e outra rota continua existindo.

As filas `.retry` de consumidor usam TTL de cinco segundos e voltam pelo exchange direto de redelivery somente à fila correspondente. Rejeição definitiva vai à DLQ desse consumidor. Essa infraestrutura foi testada; o consumidor de revogação 7A usa requeue para falhas transitórias, sem passar por `.retry`. O retry do publicador é controlado pelos campos da Outbox.

## Validação

O build usa RabbitMQ 4.3.6, PostgreSQL e Vault temporários. Cobre publicação persistente, retorno sem rota, concorrência, lease vencido, duplicata após perda simulada da confirmação no banco, indisponibilidade/recuperação do broker, worker agendado, ativação pelo Spring, TTL e DLQ. NACK e timeout também têm testes unitários.

Não é necessário interromper a stack persistente para executar esses testes. Retenção, métricas e recuperação operacional estão na [6C](operacao-outbox-local.md); efeitos distribuídos continuam na etapa 7. Ver [ADR-020](../adr/020-publicacao-outbox-rabbitmq.md) e [evidências](../evidencias/etapa-06b.md).

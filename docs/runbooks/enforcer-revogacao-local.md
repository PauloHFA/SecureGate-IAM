# Consumidor de revogação local — 7A

O switch de consumo agora habilita também a fila de bloqueio/política da [7B](enforcer-bloqueio-politica-local.md). Este guia descreve os efeitos de revogação.

## Executar

Com a stack local disponível e build validado, inicie o IAM em uma janela:

```powershell
.\infra\scripts\Start-IamCore.ps1 -EnableAuthorization -EnableBootstrap -EnableOutboxPublisher
```

Em outra janela:

```powershell
.\infra\scripts\Start-PolicyEnforcer.ps1 -EnableConsumer
```

O script lê RabbitMQ/Redis do `.env`, sem imprimir credenciais nem passá-las na linha de comando, e restaura o ambiente ao sair. Não há nova credencial, migração ou servidor HTTP. Sem o switch, o consumidor permanece desligado e a aplicação sem trabalho pode encerrar normalmente. Com o switch, o worker mantém o processo até Ctrl+C.

O JAR esperado é `policy-enforcer/target/policy-enforcer-0.1.0-SNAPSHOT.jar`. Gere com `mvnw.cmd -B -ntp clean verify`. Não remova volumes nem limpe o Redis para atualizar o serviço.

## Exercitar

Siga o [guia de refresh/revogação](oauth-refresh-local.md) para revogar access token ou família, ou encerre uma sessão autenticada conforme o [guia de eventos](eventos-seguranca-local.md). A Outbox deve preencher `published_at`; `enforcer.revocation` deve ser drenada após o Redis aceitar o efeito.

O painel RabbitMQ fica em `http://127.0.0.1:15672`, no vhost configurado. Observe fila principal, redeliveries e `enforcer.revocation.dlq`. `enforcer.block-policy` é consumida pela 7B no mesmo processo; `audit.persist` continua acumulando até implementar seu consumidor.

As marcas Redis desta entrega **ainda não são consultadas pelo recurso protegido no iam-core**. Revogação/introspecção locais existentes continuam funcionando, mas não há comprovação nova de revogação distribuída ou latência abaixo de dois segundos.

## Configuração

| Propriedade | Ambiente / padrão |
| --- | --- |
| `enforcer.consumer.enabled` | `ENFORCER_CONSUMER_ENABLED`, false |
| host / port / virtual-host | `ENFORCER_RABBITMQ_HOST`, `ENFORCER_RABBITMQ_PORT`, `ENFORCER_RABBITMQ_VHOST` |
| username / password | `ENFORCER_RABBITMQ_USERNAME`, `ENFORCER_RABBITMQ_PASSWORD` |
| `enforcer.consumer.batch-size` | 20; limite 1–100 |
| `enforcer.consumer.poll-millis` | 500; limite 100–60000 ms |
| `spring.data.redis.host` / port / password | `ENFORCER_REDIS_HOST`, `ENFORCER_REDIS_PORT`, `ENFORCER_REDIS_PASSWORD` |

O script preenche host/porta/vhost/credenciais com as configurações locais e controla enabled pelo switch. Timeout de conexão/comando Redis e RPC/conexão RabbitMQ são de dois segundos. Um ciclo não processa mais que o lote configurado e interrompe ao encontrar erro transitório.

## Estado aplicado

| Evento | Identificador da marca | Expiração |
| --- | --- | --- |
| token.revoked ACCESS_TOKEN | jti | expiresAt + 60 segundos |
| token.revoked AUTHORIZATION | authorizationId | expiresAt + 60 segundos |
| session.terminated | sid | Permanente, sem prazo seguro no contrato atual |

Chaves de efeito: `securegate:{revocation}:<jti|authorization|sid>:<SHA-256 do identificador UTF-8>`. Seu valor é o vencimento Unix em milissegundos; `0` representa tombstone permanente. Não armazena JWT bruto, senha, e-mail ou payload. Conclusões por eventId usam namespace `event` no mesmo hash slot e fingerprint SHA-256 do corpo. Conserve identificadores fora de logs públicos.

Uma mensagem mais antiga não reduz TTL. Duplicata pode reconstruir projeção ausente enquanto ainda válida. Isso não reconstrói o conjunto de revogações após perda completa do Redis: não execute FLUSHDB, expurgo de tombstones ou políticas de eviction como recuperação rotineira. Recuperação/reconciliação após perda de dados e retenção segura de sid permanecem pendentes.

## Falhas

- Redis indisponível/timeout: efeito não é dado como concluído pelo consumidor; NACK/requeue mantém a mensagem para o próximo ciclo. Se o comando executar após o timeout do cliente, redelivery é segura por idempotência.
- Broker indisponível: worker registra somente classe do erro e tenta reconectar em ciclo posterior. Mensagem cujo efeito já foi aplicado, mas ACK foi perdido, pode reaparecer.
- Contrato/cabeçalhos inválidos ou produtor não suportado: mensagem vai à DLQ. Corrija a causa antes de replay.
- Mesmo eventId com bytes diferentes: conflito vai à DLQ sem aplicar o segundo conteúdo. Preserve os bytes originais ao repetir publicação.
- DLQ: investigue sem imprimir payload sensível; não republique automaticamente toda a fila. O encaminhamento classic local não comprova tolerância a toda falha de dead-lettering.

Contadores internos: `iam.enforcer.revocation.messages` com result ACK/REJECT/RETRY e `iam.enforcer.revocation.connection.errors`. São locais ao processo; esta aplicação não expõe endpoint HTTP de métricas. Métricas administrativas da Outbox permanecem no [IAM](operacao-outbox-local.md).

Testes usam RabbitMQ e Redis descartáveis. Não interrompem a stack persistente. Veja [ADR-022](../adr/022-consumidor-de-revogacao.md) e [evidências](../evidencias/etapa-07a.md).

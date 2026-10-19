# ADR-020 — Publicação da Outbox com confirmação RabbitMQ

- Data: 2026-10-19
- Estado: implementada localmente; publicação/CI remota/revisão pendentes
- Entrega: 6B

## Decisão

Adicionar um publicador opcional ao `iam-core`. `OutboxPublisher` coordena as portas `OutboxDelivery` e `EventTransport`, sem depender de Spring ou do cliente AMQP. O adaptador JDBC controla leases e o adaptador RabbitMQ publica mensagens persistentes, com `mandatory` e publisher confirms. O cliente Java 5.25.0 é gerenciado pelo BOM Spring Boot existente; não foi adicionado starter AMQP ou consumidor.

O publicador fica desabilitado por padrão. Quando habilitado, um worker executa lotes limitados em uma thread própria, com atraso entre ciclos. A conexão é aberta sob demanda, sem incluir o broker na transação HTTP ou bloquear a inicialização por tentativa de conexão. Erros do ciclo são registrados somente pelo tipo da exceção e permitem ciclos seguintes. No encerramento, o worker é interrompido, aguarda até cinco segundos e a conexão é fechada; leases remanescentes permitem recuperação posterior.

## Claim e concorrência

A V11 adiciona `attempts`, `next_attempt_at`, `claim_id`, `lease_until` e `last_error_code`, além de restrições e índice de pendências por data de tentativa. A aquisição usa `FOR UPDATE SKIP LOCKED` e UPDATE na mesma transação curta. Cada claim recebe UUID novo e incrementa tentativas, com saturação em um milhão para evitar overflow.

O relógio do PostgreSQL determina elegibilidade e expiração; diferenças entre relógios de instâncias não antecipam leases. Nenhuma transação de banco permanece aberta durante I/O AMQP. O adaptador recusa claim dentro de transação de negócio ativa. Cada aquisição, marcação e reagendamento confirma independentemente sua transação.

A marcação exige eventId, claimId atual, lease ainda válido e ausência de published_at. Um worker atrasado não confirma nem reagenda o claim de outro. Se a publicação exceder o lease, pode haver reenvio, mas o worker antigo não sobrepõe o novo estado. Falha após ACK do broker e antes de confirmar o banco também pode duplicar a entrega. A garantia é **ao menos uma vez**; consumidores precisarão de idempotência por eventId na etapa 7.

## Confirmação e retorno

Cada envio usa um canal dedicado, modo de confirmação e mensagem persistente para `security.events`, com eventType como routing key. O corpo é o texto JSONB lido da Outbox, sem reconstruir o envelope ou gerar novo ID. Metadados incluem messageId=eventId, correlationId, tipo, produtor, schemaVersion, content-type e timestamp. A leitura valida envelope, dados conhecidos e correspondência dos cabeçalhos com a linha persistida.

Somente ACK sem retorno mandatory autoriza preencher `published_at`. RabbitMQ envia `basic.return` antes do ACK correspondente quando não existe rota; por isso um ACK acompanhado de retorno continua sendo falha. NACK, timeout e desconexão conservam a pendência. Essa regra segue a documentação de [publisher confirms do RabbitMQ](https://www.rabbitmq.com/docs/confirms).

Confirmação significa aceitação pelo broker e existência de pelo menos uma rota. Não comprova conclusão dos consumidores nem que todas as filas pretendidas continuem vinculadas. A topologia é declarada ao abrir a conexão; sua integridade durante a operação e alertas fazem parte da entrega operacional. Uma alteração manual que remova somente um binding pode não produzir retorno se outra fila ainda receber o evento.

## Retry e falhas

Falhas usam códigos fechados: UNROUTABLE, NACK, CONFIRM_TIMEOUT, BROKER_UNAVAILABLE ou INVALID_EVENT. Não são gravados payload, senha, URI autenticada ou mensagem livre da exceção como erro. O PostgreSQL reagenda tentativas com backoff de 2, 4, 8, 16, 32, 64, 128, 256 e até 300 segundos, preservando identidade e payload.

O retry de publicação fica na Outbox. As filas `.retry` e seus TTLs pertencem à futura recuperação dos consumidores. Esta entrega mantém eventos com falha para novas tentativas; não os apaga nem marca como publicados após um número máximo. Tratamento operacional de eventos permanentemente inválidos, retenção, métricas e reprocessamento serão a 6C.

Timeout de confirmação e RPC são configurados, e o cliente usa NIO e recuperação automática desabilitada: a nova tentativa passa pelo estado persistido da Outbox. A infraestrutura pode tornar um resultado de envio ambíguo; o lease e a confirmação condicional preservam a segurança diante dessa ambiguidade, sem prometer um prazo absoluto de execução de toda operação de rede.

## Topologia

O JAR empacota somente `infra/rabbitmq/definitions.json`, a definição sem usuários/senhas, em `rabbitmq/definitions.json`. O adaptador declara os mesmos exchanges, filas duráveis, bindings, TTLs e DLQs no vhost configurado. Incompatibilidade ou falta de permissão impede concluir a conexão/publicação e conserva o evento pendente.

Foram acrescentados bindings de `auth.login.failed` e `identity.user.*` para `enforcer.block-policy`, preparando tentativas de login e evolução de estado/versão, inclusive desbloqueio. Revogação e encerramento continuam em `enforcer.revocation`; auditoria recebe `#`. A topologia `.retry` usa TTL de cinco segundos e redelivery direto para a fila de origem, preservando a separação entre consumidores. Nenhum efeito de bloqueio ou ingestão foi implementado nos consumidores.

## Configuração e limites

`iam.outbox.publisher.enabled` ativa o publicador. O script local oferece `-EnableOutboxPublisher`, lê as credenciais RabbitMQ do `.env` existente, transmite-as por ambiente e restaura o ambiente ao sair. Não grava segredos no JAR, em argumentos Java ou na definição empacotada. As configurações têm validação de limites e diagnóstico com credenciais omitidas.

Padrões: timeout 2000 ms, lease 30 s, lote 20 e intervalo 1000 ms. O lease configurado deve ser ao menos quatro vezes o timeout. O perfil local usa AMQP TCP da infraestrutura existente; TLS, permissões mínimas de produção e imagens endurecidas permanecem na etapa 11.

Sem limpeza automática, métricas de backlog, consumidores Redis, processamento de auditoria por mensagem, benchmark ou meta de propagação medida. Não há ordenação global entre workers/retries. A 6B não encerra a etapa 6; a entrega seguinte é a 6C.

Ver [guia](../runbooks/publicacao-outbox-local.md), [evidências](../evidencias/etapa-06b.md) e [plano](../PLANO-DE-IMPLEMENTACAO.md).

# Tracing do outbox — 10B3c1/c2

## Preparação e implantação

Usar JDK 21/Wrapper e concluir a preparação do banco já documentada. O schema atual passa a **V23**, aditivo. Compilar o JAR antes da migração. A captura usa os perfis http-tracing/structured-logs da [etapa HTTP](tracing-http-local.md), sem nova flag/exporter.

1. Planejar a migração e o lock ALTER TABLE com a identidade de migração; não usar login runtime para DDL.
2. Parar/atualizar o runtime conforme a janela do ambiente. Executar o procedimento de migração existente antes do novo binário, inclusive com tracing desligado.
3. Em ambiente local já preparado, o comando de migração é o abaixo. Ele altera o banco configurado no .env; não foi executado contra a stack persistente nesta entrega.
4. Conferir Flyway em V23 e existência da coluna nullable/constraint/trigger. Depois iniciar IAM com os parâmetros/identidades habituais; em modo de credenciais dinâmicas, Flyway continua separado.
5. Sem tracer/span, novos eventos têm NULL. Sob tracing HTTP, cada append captura o contexto corrente na transação do evento.

```powershell
# Operação sobre o banco configurado; executar somente na janela de migração planejada:
.\infra\scripts\Migrate-Database.ps1
```

SELECT/INSERT da identidade runtime continuam cobrindo a tabela; não conceder UPDATE(trace_parent). Não é necessário alterar RabbitMQ, Redis ou Vault. Não imprimir valores de tokens/headers ao diagnosticar eventos.

## Contrato de persistência

trace_parent tem até 55 caracteres: 00-<32 hex>-<16 hex>-00/01, letras minúsculas, IDs não nulos; NULL permitido. Não há JSON de headers, baggage, tracestate, URL, body ou credencial nesse campo. correlationId é outro identificador; o envelope SecurityEvent v1 permanece intacto. IDs de trace podem ser escolhidos pelo cliente e não autorizam operações. Não colocar dados pessoais/segredos neles nem utilizá-los como labels de métricas.

O parent é imutável após o INSERT. ACK/retry/lease/quarentena continuam alterando somente estado de entrega; retries recuperam o valor original, sem substituí-lo pelo contexto do worker. O trigger recusa mudança inclusive pelo owner enquanto habilitado; superuser/owner com controle de DDL não é impedido de modificar a política. O Claim Java expõe traceParent para a publicação e omite-o do toString.

Linhas existentes e produtores sem contexto ficam NULL; não há backfill. Valor de fornecedor fora do formato é descartado para NULL pelo adapter, e SQL rejeita inserção inválida direta. Na 10B3c1 nenhum contexto era enviado ao RabbitMQ; a 10B3c2 acrescenta o header de publicação descrito abaixo. Consumo correlacionado permanece na 10B3c3.

## Publicação AMQP — 10B3c2

1. Aplicar V23 antes do runtime conforme o procedimento acima; não há V24 nem alteração de topologia RabbitMQ nesta entrega.
2. Usar a configuração de publicador existente (iam.outbox.publisher.enabled=true e credenciais/permissões habituais). Habilitar também o perfil http-tracing para criar o tracer; manter as demais opções de execução necessárias ao ambiente. Não expor senha na linha de comando/log. O perfil de tracing sozinho não inicia o worker.
3. Sem tracing, enviar somente schemaVersion no mapa de headers, mesmo quando a coluna trace_parent contém valor. Com tracing, cada tentativa inicia span outbox.publish PRODUCER e envia seu próprio traceparent, além de schemaVersion.
4. Parent válido na coluna determina o traceId/parentSpanId. NULL/inválido cria raiz, sem herdar o span do worker. Nenhum mapa externo de headers é copiado. Baggage, tracestate e B3 não são publicados.
5. Confirm, mandatory return, NACK, timeout, interrupção e indisponibilidade preservam seus resultados anteriores. O escopo é restaurado/limpo; somente messaging.system e messaging.outcome entram no span. O body JSON v1, messageId e correlationId não recebem dados novos.
6. Em retry, conferir por testes que parent durável/body/eventId permanecem iguais, mas spanId do produtor muda. Confirm não significa consumidor concluído; queda antes do UPDATE ainda permite redelivery. Não registrar headers/credenciais ao diagnosticar.
7. Desabilitar tracing e reiniciar o runtime para suspender spans/headers; a coluna e os eventos existentes permanecem. Não remover a migração nem alterar o payload para reverter.

Não há exporter/backend instalado: spans de produção não são armazenados para consulta. Os testes capturam spans localmente e verificam o header entregue pelo broker. O enforcer passa a extrair o header na [10B3c3a](tracing-consumidores-local.md); auditoria IAM em lote permanece na 10B3c3b.

## Verificação

```powershell
.\mvnw.cmd -B -ntp -pl iam-core -am verify '-Dtest=OutboxTraceParentTest,OutboxPublisherTest,RabbitEventTransportTest,IamArchitectureTest,ArchitectureRulesTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=OutboxTraceContextIT,HttpServerTracingIT,OutboxPublicationIT,RuntimeDatabasePrivilegesIT' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djacoco.skip=true'
.\mvnw.cmd -B -ntp verify
```

OutboxTraceContextIT usa PostgreSQL real e contexto SDK: upgrade V22 → V23 com evento existente, auditoria/outbox revertidos juntos, NULL/invalid, restrições SQL, retry e nova instância de store com parent/payload preservados. Não reinicia JVM. HttpServerTracingIT registra via HTTP IAM real e confere parent do span servidor no banco, fora do JSON. Testes anteriores de publicação usam RabbitMQ real e preservam ACK/lease/retry/evento; na revisão 10B3c1 não demonstravam propagação de tracing AMQP. A 10B3c2 estende OutboxPublicationIT com reentrega após confirm e parent durável, usando broker e PostgreSQL reais; RabbitEventTransportTest usa SDK real e canal simulado para verificar atributos, sampler, headers e fechamento de escopo. RuntimeDatabasePrivilegesIT recusa UPDATE(trace_parent) com login restrito. verify completo inclui gates existentes; smokes opt-in pelo nome continuam fora.

## Reversão e próximos passos

Para parar a captura, desativar tracing e reiniciar o IAM; metadados já persistidos permanecem. Não remover a coluna nem reescrever histórico Flyway para reverter o binário. Aplicação anterior usa INSERT de campos nomeados e deixa a coluna em NULL; avaliar compatibilidade da versão exata no processo de release. Retenção do parent acompanha a linha de outbox; nenhuma retenção nova foi criada.

A [10B3c3a](tracing-consumidores-local.md) implementa restauração/limpeza no enforcer. Próxima proposta: **10B3c3b** tratar contexto no consumo de auditoria IAM em lote, preservando atomicidade, isolamento e retries/redelivery. A publicação da 10B3c2 está descrita na [ADR-061](../adr/061-tracing-publicacao-amqp.md) e nas [evidências 10B3c2](../evidencias/etapa-10b3c2.md). Exporter/backend e aceite de desempenho permanecem pendentes. [ADR-060](../adr/060-contexto-duravel-no-outbox.md), [evidências](../evidencias/etapa-10b3c1.md).

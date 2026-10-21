# Evidências — 10B3c2: publicação AMQP

- Data: 21/10/2026, America/Sao_Paulo.
- Base: 57f34bf; branch feat/outbox-trace-context.
- Estado: implementação e validação locais concluídas; incluída neste commit; push/CI remota pendentes.

## Alterações

RabbitEventTransport recebe Tracer opcional, cria span PRODUCER outbox.publish por tentativa a partir do parent durável ou raiz explícita, adiciona somente traceparent além de schemaVersion e fecha escopo/span em sucesso/falha. Dois atributos finitos: messaging.system e messaging.outcome. OutboxPublisherConfiguration conecta o tracer opcional; construtor anterior mantém comportamento sem tracing. Nenhuma mudança do payload v1, banco, publisher ACK/retry/lease, consumidores, .env ou stack persistente.

Cinco novos testes unitários com SDK real e canal RabbitMQ simulado: default mesmo com parent persistido, retries com filhos distintos e worker não herdado, NULL/contexto hostil com raízes, sampled remoto versus sampler local zero, fechamento em timeout/interrupção/IOException/JSON inválido. Testes anteriores cobrem mandatory return/NACK/topologia. Integração adicional em OutboxPublicationIT usa PostgreSQL e RabbitMQ reais: captura na transação audit/outbox, claim, confirm sem atualização, expiração da lease e nova instância de store/transport, reentrega com identidade/body iguais e novo span produtor. A queda é simulada; não há reinício de JVM.

## Validação

Seleção: BUILD SUCCESS às 20:22:42 UTC−03, 1 min 9 s, exit code nativo 0. **31 testes unitários/arquitetura + 18 integrações**, zero falhas/erros/skips. Integrações: OutboxPublicationIT 13 e OutboxTraceContextIT 5. JaCoCo desativado apenas nessa seleção.

Build completo: **BUILD SUCCESS às 20:33:29 UTC−03**, 10 min 25 s, exit code nativo **0**. **824 testes**, zero falhas/erros/skips: security-events 43, IAM 405 unitários + 299 integrações, enforcer 44 unitários + 22 integrações, ponta a ponta PropagationIT 11. Totais conferidos no log desta execução; XMLs antigos de smokes opt-in não foram somados. Os **dois gates JaCoCo** foram atendidos e os testes de arquitetura passaram.

Sem aviso de timeout de encerramento do fork nesta execução; não significa correção da ressalva histórica Hikari. A seleção se sobrepõe ao build completo; não somar os totais. Nenhum código foi alterado após o início do verify completo; somente documentação de resultados.

Conferência final: 12 arquivos alterados/novos UTF-8 sem BOM ou whitespace final; 1.344 links locais da documentação sem destino quebrado; git diff --check limpo. Logs de execução ficam no diretório local ignorado .firecrawl, sem inclusão no Git.

```powershell
.\mvnw.cmd -B -ntp -pl iam-core -am spotless:apply '-Dtest=RabbitEventTransportTest,OutboxPublisherTest,OutboxTraceParentTest,IamArchitectureTest,ArchitectureRulesTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=OutboxPublicationIT,OutboxTraceContextIT' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djacoco.skip=true' verify
.\mvnw.cmd -B -ntp verify
```

## Limites

Sem push, CI ou merge consultados. Stack persistente não migrada; V23 já introduzida na 10B3c1 deve preceder o binário. Sem exporter, leitura de spans em produção, consumo correlacionado IAM/enforcer ou coleta sustentada/medição de desempenho. Smokes opt-in pelo nome não são implicados por verify. Ressalva histórica de shutdown Hikari não é declarada corrigida por esta mudança.

[ADR-061](../adr/061-tracing-publicacao-amqp.md), [operação](../runbooks/tracing-outbox-local.md), [guia](../GUIA-DE-IMPLEMENTACAO.md).

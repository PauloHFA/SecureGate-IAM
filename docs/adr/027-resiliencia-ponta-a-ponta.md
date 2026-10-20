# ADR-027 — Falhas e replay na cadeia de enforcement

- Estado: aceita para implementação local na 7D2.
- Data: 2026-10-19.

## Decisão

Estender a suíte da [7D1](026-medicao-ponta-a-ponta.md), mantendo os JARs reais e infraestrutura descartável. A entrega acrescenta cinco cenários de integração, sem mudar o comportamento ou a configuração padrão dos serviços.

1. **Enforcer interrompido:** uma revogação confirmada e publicada permanece na fila, sem projeção antecipada. Reiniciar o mesmo serviço recupera a entrega. Três cópias exatas da mensagem original preservam o efeito e o fingerprint. Um logout real posterior na mesma fila confirma que as cópias anteriores foram processadas.
2. **Broker indisponível:** parar somente a aplicação RabbitMQ do contêiner de teste; revogação HTTP continua transacional, a Outbox registra falha de publicação e a projeção permanece ausente. Reiniciar o broker permite publicar/consumir o mesmo eventId, sem nova chamada de revogação.
3. **Redis indisponível:** pausar somente o contêiner Redis descartável; recurso responde 503, e uma revogação real publicada permanece recuperável na fila. Despausar permite projeção e 401, sem encaminhar a entrega válida à DLQ. Não se exige latência saudável durante a interrupção.
4. **Política fora de ordem:** criar ALLOW, atualizar para DENY e depois ALLOW por HTTP; observar 403 e retorno a 200. Reentregar cópias antigas de DENY e ALLOW depois da versão nova conserva o watermark e o acesso atual.
5. **Falhas reais de login:** reentregar a primeira falha não aumenta o contador além de uma tentativa. Cinco tentativas por conta e vinte por IP produzem bloqueios nos fluxos de login/recurso/refresh. Recusas prévias por IP não produzem fatos fictícios de senha. Expiração natural permite recuperar recurso/refresh, inclusive usando o refresh que foi recusado.

## Controle das falhas e do replay

As operações afetam somente processos e contêineres criados pela fixture. Não se lê `.env`, não se toca na stack persistente e não se apagam marcas Redis. Recuperação usa `finally`. Reinícios preservam os logs locais e as credenciais geradas da mesma execução.

O replay captura corpo e propriedades originais na fila `audit.persist` descartável, depois da confirmação de publicação. Nenhuma fila do enforcer é consumida pelo teste. A cópia volta ao exchange original com o mesmo eventId, corpo, tipo, cabeçalhos e correlação. Não se fabrica um fato nem se altera o payload para evitar conflitos de fingerprint.

Uma marca já existente não prova que uma cópia foi processada. Por isso, após o replay, o teste produz um novo evento real na mesma fila e aguarda sua conclusão: o consumidor serial precisa atravessar as cópias anteriores antes desse evento. Confere também ausência de rejeições na DLQ de teste.

## Limiares e prazos

Somente no último cenário, reiniciar o enforcer com bloqueio de três segundos permite testar expiração sem esperar os 300 segundos padrão. Permanecem os limiares padrão 5/20 e a janela de 300 segundos. Essa alteração pertence à fixture; não muda configuração do projeto, scripts ou ambiente persistente. O cenário fica por último porque compartilha o IP do par conectado com os anteriores.

A medição saudável de nove amostras da 7D1 continua usando os padrões originais. Recuperação de falhas tem espera limitada, separada do critério de dois segundos: não transforma indisponibilidade deliberada em violação ou aprovação da latência saudável.

## Limites

Os testes demonstram os cenários exercitados em uma instância de cada serviço. Não simulam perda permanente de dados, reconstrução Redis, eleição/cluster, saturação, todas as permutações de eventos ou sessões compartilhadas. A reordenação ponta a ponta usa política; os testes anteriores do consumidor continuam cobrindo identidade, timestamps e versões exatas.

O rate limiting continua assíncrono e dependente de propagação; não é limite rígido de QPS. 401/403 também podem decorrer das barreiras locais do IAM: a evidência de propagação exige separadamente publicação e efeito Redis. Aprovação local não substitui publicação, CI remota e revisão.

Referências: [guia](../runbooks/resiliencia-ponta-a-ponta-local.md), [evidências](../evidencias/etapa-07d2.md) e [plano](../PLANO-DE-IMPLEMENTACAO.md).

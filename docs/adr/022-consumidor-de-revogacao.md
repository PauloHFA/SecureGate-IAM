# ADR-022 — Consumidor de revogação e projeção Redis

- Estado: aceita para a entrega local 7A.
- Data: 2026-10-19.
- Base: [publicação da Outbox](020-publicacao-outbox-rabbitmq.md) e [operação](021-operacao-da-outbox.md).

## Escopo

O módulo `policy-enforcer` torna-se uma aplicação Spring Boot sem servidor HTTP, com consumo opcional de `enforcer.revocation`. A 7A cobre somente `token.revoked` e `session.terminated`; não consome a fila de bloqueio/política nem modifica a decisão de acesso do `iam-core` nesta entrega.

Um trabalhador usa `basicGet` com ACK manual, no máximo uma mensagem em processamento por instância e lotes/intervalos limitados. Os padrões são 20 mensagens por ciclo e pausa de 500 ms. O polling simplifica o controle de canal e recuperação inicial; vazão precisa ser medida antes de produção. O ciclo seguinte reconecta após falha de canal/broker. Encerrar conexão devolve mensagens não confirmadas.

## Validação e confirmação

O leitor aceita apenas envelope v1 com no máximo 64 KiB, produtor `iam-core`, campos conhecidos e dados validados pelo contrato compartilhado. Cabeçalhos messageId, type, appId, correlationId e schemaVersion devem coincidir com o corpo; conteúdo JSON UTF-8 e entrega persistente são exigidos. A identificação do produtor é metadado, não autenticação criptográfica; permissões do broker continuam sendo a fronteira de confiança.

O ACK só ocorre após o script Redis retornar aplicação ou duplicata. Erro Redis mantém a mensagem via NACK/requeue e encerra o lote: nova tentativa ocorre no próximo ciclo, sem descarte por limite de tentativas. Esta fila de revogação prioriza preservar pendências durante indisponibilidade; não usa `.retry` com TTL nesta entrega. Falhas de contrato ou conflito de eventId são rejeitadas sem requeue e encaminhadas pela topologia existente à DLQ. Em filas classic de nó único, dead-lettering não demonstra entrega garantida sob toda falha do broker.

## Efeito e idempotência

Um script Lua aplica o efeito terminal antes de registrar a conclusão. Chave de deduplicação e chave do efeito compartilham o hash slot `{revocation}`. O valor de deduplicação é SHA-256 dos bytes originais: mesmo eventId com outros bytes é conflito, inclusive diferenças de serialização. O produtor da Outbox preserva os bytes nas tentativas.

Duplicatas válidas verificam/reconstituem também a projeção, sem reduzir seu prazo. A operação é atômica quanto à visibilidade no Redis. Lua não promete rollback de comandos já executados em todo erro: por isso a conclusão é gravada por último; um efeito escrito antes de falha no marcador pode ser reaplicado com segurança. Não há afirmação de processamento exatamente uma vez.

| Fato | Projeção | Prazo |
| --- | --- | --- |
| token.revoked / ACCESS_TOKEN | `jti` | expiresAt + 60 s de tolerância |
| token.revoked / AUTHORIZATION | authorizationId | expiresAt + 60 s de tolerância |
| session.terminated | sid | Sem expiração automática |

Revogações são comutativas: expiração menor nunca substitui uma maior e nenhum evento remove uma marca terminal. Eventos já expirados não criam bloqueio novo, mas podem registrar deduplicação. Um sid encerrado não recebe prazo fictício: o contrato atual não informa o maior vencimento de seus grants, portanto o tombstone permanece até existir uma política segura de limpeza. Isso exige acompanhar crescimento de memória.

As chaves seguem `securegate:{revocation}:<jti|authorization|sid>:<sha256-do-identificador-UTF8>`. Conclusões usam `securegate:{revocation}:event:<eventId>` e ficam por pelo menos sete dias ou até o vencimento do efeito, o que for maior. Após essa janela, replay pode ser reaplicado; os efeitos continuam idempotentes. O hash slot único é uma escolha local, não uma demonstração de escala em Redis Cluster.

## Operação e limites

O worker é desabilitado por padrão e usa as credenciais já existentes da stack local por variáveis de ambiente. O JAR empacota somente a definição base sem credenciais e declara exchanges/filas/bindings da revogação. Não há conexão com o banco do IAM ou Vault. Contadores internos têm tags fechadas ACK/REJECT/RETRY e erro de conexão, sem payload ou identificadores.

A stack local usa AOF no Redis, mas a 7A não garante sobrevivência a perda de dados, remoção administrativa de chaves, failover ou perda de persistência. Evite eviction de projeções de segurança; recuperação/reconciliação e comportamento do recurso com Redis indisponível precisam da integração seguinte. Não limpe o Redis para resolver uma falha do consumidor. DLQ exige investigação de contrato/conflito antes de replay.

TLS, credenciais separadas por serviço, Prometheus, tracing e endurecimento operacional pertencem às entregas de produção. A meta de revogação em menos de dois segundos continua pendente de consulta no recurso e medição ponta a ponta.

Referências: [guia local](../runbooks/enforcer-revogacao-local.md) e [evidências 7A](../evidencias/etapa-07a.md).

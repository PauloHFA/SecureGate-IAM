# ADR-059 — Tracing dos clientes HTTP Vault

- Estado: implementação local 10B3b2; validação completa concluída; incluída neste commit; push pendente.
- Base: d0ef5e2; branch feat/vault-http-client-tracing.

## Decisão e escopo

Adicionar VaultHttpTransport na infraestrutura compartilhada e injetar nos clientes runtime de Transit/MFA, sessões de banco dinâmico e PAM. O transporte continua síncrono JDK 21, com clientes/conexões/limites/serializadores existentes. Construtores anteriores permanecem sem tracing para uso explícito/testes; os beans Spring recebem o transporte configurado. KV do EnvironmentPostProcessor fica fora: executa antes do contexto/tracer e trata bootstrap de segredos, sem parent HTTP.

A configuração recebe Tracer/Propagator opcionais por parâmetros de bean, preservando a ordem de destruição datasource/cliente → transporte → tracer/provider. Não procurar beans durante a revogação no shutdown. Sem tracer, enviar a requisição original sem criar contexto ou modificar headers. Com http-tracing ativo, copiar a requisição removendo traceparent/tracestate/baggage/B3 anteriores. Criar span e injetar somente traceparent quando esquema/host/porta efetiva coincidirem com o Vault configurado, path corresponder ao catálogo, não houver userinfo/query/fragment e o cliente usar Redirect.NEVER. Destinos/rotas inelegíveis continuam com o comportamento de transporte existente, sem contexto novo; a lista limita tracing, não autoriza rede/credenciais.

Spans CLIENT de nome vault.http, filhos do contexto atual ou raízes em startup/maintenance sem parent. Não reter contexto em token, sessão, lease ou scheduler. Escopo sempre fechado antes de finalizar o span, inclusive em erro. A amostragem é a mesma da 10B3b1. Sem novo exporter, fila, backend ou dependência Maven.

## Dados permitidos

Somente quatro atributos: vault.operation (approle.login, token.renew, token.revoke, transit.keys/sign/encrypt/decrypt, database.issue, pam.issue, lease.renew/revoke), http.method (métodos conhecidos ou OTHER), http.status_class (1xx–5xx/unknown) e http.failure (true/false). Não exportar URI/host/IP/key name/role/lease ID, headers, tokens, SQL, body, ciphertext, credenciais ou mensagens/stack traces. Não chamar Span.error/recordException.

O span cobre envio e recebimento do BodyHandler; JSON/validação criptográfica subsequentes ficam fora da duração e resultado de transporte. Non-2xx é falha; IOException/interrupção têm status unknown e são relançadas para o tratamento já existente, que mantém sua política de interrupção/falha fechada. Não criar spans para rejeições locais do semáforo antes do envio. Não seguir redirects nem acrescentar retries.

## Verificação e limites

Unitários com servidor HTTP local verificam corpo/token preservados, ausência de contexto por padrão, parent/child/restauração, inelegibilidade, 403/500/302 e exceções sem texto sensível. Integração com IAM/PostgreSQL reais e Vault HTTP simulado verifica sessão AppRole → consulta → revogação como três filhos do servidor, sem baggage/tracestate ou segredos nos SpanData. Não é evidência de tracing dentro do processo Vault. Integrações funcionais Vault reais existentes exercitam os clientes alterados no build completo, por padrão sem tracing; não alegar teste real de metadata para cada operação/role.

[Operação](../runbooks/tracing-http-local.md), [evidências](../evidencias/etapa-10b3b2.md). Contratos JDK 21: [cópia de HttpRequest](https://docs.oracle.com/en/java/javase/21/docs/api/java.net.http/java/net/http/HttpRequest.html), [HttpClient/send e lifecycle](https://docs.oracle.com/en/java/javase/21/docs/api/java.net.http/java/net/http/HttpClient.html). Continuidade assíncrona, contexto outbox/AMQP (10B3c), exporter/backend e capacidade continuam pendentes.

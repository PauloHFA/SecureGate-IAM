# Tracing HTTP — entrada 10B3b1 e clientes Vault 10B3b2

## Ativação

Usar JDK 21 e o comando java -jar já documentado para o IAM, com seus arquivos privados habituais. Acrescentar perfis à lista existente:

```powershell
# Argumento adicional ao comando habitual:
# --spring.profiles.active=structured-logs,http-tracing
# Ajuste opcional no ambiente do processo (sem credenciais):
$env:IAM_HTTP_TRACE_PROBABILITY = '0.1'
```

Padrão: management.tracing.enabled=false, sem Tracer/SdkTracerProvider. Perfil http-tracing habilita beans explícitos; a propriedade também pode ser ligada diretamente. structured-logs é independente e necessário para o schema JSON restrito. Retirar http-tracing ou configurar management.tracing.enabled=false e reiniciar para reverter. Não precisa reiniciar Vault, RabbitMQ, Redis ou PostgreSQL. Não adicionar agente/exporter para executar esta etapa; ela não realiza envio de traces à rede. O provider só fornece contexto/duração em memória; sem processor não há armazenamento de spans concluídos.

IAM_HTTP_TRACE_PROBABILITY aceita valor entre 0 e 1; inválido impede startup. Padrão 0,1. Para verificação controlada local, usar 1 e restaurar depois; 0 impede registro mesmo que sampled remoto seja 1. Não é quota: a seleção é determinística por trace ID e clientes podem escolher IDs. Contexto/IDs podem existir sem gravação de span.

## Contrato HTTP e dados

Somente traceparent versão 00, 55 caracteres, hexadecimal minúsculo e IDs não nulos. Exemplo conceitual: 00-<trace de 32 hex>-<span de 16 hex>-<flags de 2 hex>. SDK cria novo span servidor filho; preserva trace ID válido. Header ausente/inválido/duplicado cria nova raiz. baggage/tracestate/B3 são ignorados; não são propagados. Versões futuras são deliberadamente recusadas nesta implementação.

Spans têm nome http.server, duração/IDs/kind SERVER, Resource service.name=iam-core e três atributos finitos:

| Atributo | Valores |
| --- | --- |
| http.method | GET/POST/PUT/PATCH/DELETE/HEAD/OPTIONS/TRACE ou OTHER |
| http.status_class | 1xx/2xx/3xx/4xx/5xx ou unknown |
| http.failure | true/false; true para exceção observada ou status >= 500 |

Não são exportados URL, rota, query, headers, IP, usuário, SQL, body, Throwable ou eventos de exceção. O status SDK não carrega descrição de erro; usar http.failure. 4xx não equivale automaticamente a falha do servidor. Os rótulos existentes de métricas HTTP permanecem; não se adicionam IDs como labels regulares. O registry pode associar IDs como exemplares OpenMetrics via autoconfiguração Boot; essa saída não foi validada por scrape nesta entrega.

Com structured-logs, o IAM acrescenta traceId/spanId ao schema anterior (sete campos no total). Valores inválidos/nulos viram string vazia. Desde a 10B3c3a o enforcer também tem sete campos; ativação e limites em [tracing de consumidores](tracing-consumidores-local.md). X-Correlation-ID/correlationHash continuam independentes. Os IDs não autenticam e não são secretos; não inserir senha/token/PII neles. Não há header de resposta novo. O teste tem endpoint de inspeção somente na fixture, ausente do executável de produção.

## Verificação

```powershell
.\mvnw.cmd -B -ntp -pl iam-core -am verify '-Dtest=HttpTracingConfigurationTest,RestrictedHttpTracePropagatorTest,SafeOperationalLogFormatterTest,StructuredLoggingProfileTest,CorrelationIdFilterTest,IamArchitectureTest,ArchitectureRulesTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=HttpServerTracingIT,PrometheusIT' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djacoco.skip=true'
.\mvnw.cmd -B -ntp verify
```

HttpServerTracingIT sobe IAM real, PostgreSQL descartável, segurança/servlet/observações e processor de teste em memória. Verifica parentesco, MDC, erro 500, recusa 403, duplicidade e requisição seguinte com nova raiz; processor confere dados concluídos sem segredos sintéticos. Rotas permitidas /trace-test/** só existem na configuração de teste. PrometheusIT valida runtime padrão sem tracer/provider e preserva histogramas e proteção do endpoint. Unitários testam sampler zero contra parent sampled, probabilidade inválida, parsing e logs.

A seção abaixo registra a instrumentação runtime dos clientes JDK/Vault acrescentada na 10B3b2. Contexto outbox/AMQP continua para 10B3c. Não foi testada continuidade em async servlet/futures. Stack persistente e .env não alterados. [ADR-058](../adr/058-tracing-http-entrante-restrito.md), [evidências](../evidencias/etapa-10b3b1.md).

## Clientes Vault — 10B3b2

Os mesmos perfis http-tracing/structured-logs habilitam o transporte nos beans runtime Transit/MFA, banco dinâmico e PAM. Não há flag/exporter adicional. Desligar management.tracing.enabled mantém o envio original sem contexto novo. Construtores manuais antigos continuam sem tracing; para instrumentar instâncias manuais, fornecer explicitamente VaultHttpTransport. A configuração Spring resolve dependências antes do uso, mantendo tracer/provider vivos durante a limpeza dos clientes.

Somente a origem Vault configurada (esquema, host sem resolver aliases, porta explícita ou padrão) e rotas conhecidas recebem traceparent. localhost e 127.0.0.1 não são tratados como a mesma origem. Credenciais de URI/query/fragment impedem instrumentação; os validadores existentes dos clientes continuam sendo os controles de endereço. O wrapper não é firewall: requisições inelegíveis seguem o transporte existente sem novo contexto. Clientes com redirects habilitados também ficam sem tracing; os clientes da aplicação continuam Redirect.NEVER. Em tracing ativo, headers anteriores W3C/baggage/B3 são removidos da cópia; só traceparent é injetado.

Spans CLIENT vault.http têm quatro atributos finitos: vault.operation, http.method, http.status_class e http.failure. Operações: approle.login; transit.keys/sign/encrypt/decrypt; token.renew/revoke; database.issue; pam.issue; lease.renew/revoke. Não há role/key name/lease ID/URI/SQL/token/body/credencial/evento de exceção nos spans. Os atributos do span servidor da 10B3b1 permanecem três; IDs continuam nos logs IAM e não viram labels regulares de métricas.

Sob uma requisição HTTP, cada envio é filho do span corrente, com restauração após sucesso/erro. Login, consulta e revogação de uma sessão podem criar três spans irmãos; não se guarda parent no cache de token. Operações de startup/maintenance sem parent criam raízes amostradas pela política local; não há propagação automática entre threads. Leitura KV anterior ao Spring continua sem tracing. Não há spans internos do Vault ou de consultas SQL.

Status HTTP non-2xx marca falha; IOException/interrupção marcam unknown/true sem registrar mensagens. O chamador mantém o tratamento anterior, incluindo reinterrupt e invalidação de token. O span termina após consumir a resposta pelo BodyHandler original, antes de parse/validação JSON: HTTP 200 com JSON inválido pode ter sucesso de transporte e falha de aplicação. Timeouts, semáforo, limites de resposta, renovação, revogação, retries e redirects não foram ampliados.

```powershell
.\mvnw.cmd -B -ntp -pl iam-core -am verify '-Dtest=VaultHttpTransportTest,VaultDatabaseSessionLifecycleTest,VaultDatabaseFailuresTest,VaultTransitSigningKeysTest,IamArchitectureTest,ArchitectureRulesTest,HttpTracingConfigurationTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=HttpServerTracingIT,PrometheusIT' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djacoco.skip=true'
```

A fixture de integração usa IAM/PostgreSQL reais, servidor HTTP simulando Vault e processor em memória; prova headers e relação de spans dos envios JDK reais de uma sessão. As integrações funcionais Vault existentes no verify completo exercitam os clientes sem tracing por padrão; isso é distinto de testar metadata de todos os endpoints/roles com Vault real. Backend/retensão, KV de bootstrap, async e 10B3c permanecem fora do escopo. [ADR-059](../adr/059-tracing-clientes-http-vault.md), [evidências 10B3b2](../evidencias/etapa-10b3b2.md).

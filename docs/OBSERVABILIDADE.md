# Observabilidade — inventário e contratos

## Divisão da 10B

| Entrega | Estado | Escopo |
| --- | --- | --- |
| 10B1 | Implementada localmente | Inventário, exportação Prometheus protegida no IAM, histogramas HTTP opcionais e testes. |
| 10B2a | Implementada localmente | Compose Prometheus/Grafana, datasource e dashboard IAM; teste real de scrape/consultas/credencial. |
| 10B2b1 | Implementada localmente | Listener JDK opcional do enforcer, token próprio, job/overlay e dashboard separado. |
| 10B2b2a | Implementada localmente | Plugin/scrape RabbitMQ opcionais, agregação e dashboard separado. |
| 10B2b2b | Implementada localmente | Login dedicado, exporter PostgreSQL opcional, senha por arquivo e estatísticas do cluster. |
| 10B2b2c | Implementada localmente | ACL própria sem acesso a chaves, exporter Redis opcional e estatísticas agregadas. |
| 10B2b2d | Implementada localmente | Listener Vault dev autenticado, token mínimo de 1h/rotação e coleta nativa. |
| 10B3a | Implementada localmente | Perfil JSON restrito nos dois executáveis, catálogo finito e hash de correlação; sem payload/MDC livre. |
| 10B3b1 | Implementada e validada localmente | HTTP servlet entrante opcional, W3C restrito, spans finitos e IDs nos logs IAM; sem exporter. |
| 10B3b2 | Implementada e validada localmente | Transporte dos clientes JDK runtime Vault, origem/path restritos, quatro atributos e contexto restaurado. |
| 10B3c | Proposta, pendente | Contexto durável outbox/RabbitMQ e restauração no consumo. |

A 10B1 não conclui observabilidade nem aceita desempenho. O enforcer continua com web-application-type=none; a 10B2b1 acrescenta listener JDK opcional em loopback, GET /metrics, sem MVC/Actuator. Default sem listener. A 10B2a acrescenta Compose Prometheus/Grafana validado com IAM real em stack descartável, sem coleta sustentada ou alteração dos serviços persistentes do usuário. A 10B2b2a acrescenta plugin opcional do RabbitMQ; a 10B2b2b acrescenta exporter PostgreSQL e login de monitoração; a 10B2b2c acrescenta ACL/exporter Redis; a 10B2b2d acrescenta listener/token Vault próprios. Execução conjunta sustentada ainda não foi validada.

## IAM: superfície e controle de acesso

A dependência micrometer-registry-prometheus usa a versão gerenciada pelo Spring Boot do projeto; não há versão paralela fixada. Endpoint e histogramas são opt-in:

| Configuração | Padrão | Efeito |
| --- | --- | --- |
| IAM_PROMETHEUS_ENABLED | false | Habilita GET /actuator/prometheus e a cadeia de segurança dedicada. |
| IAM_METRICS_TOKEN_FILE | vazio | Arquivo privado UTF-8 com token de leitura; obrigatório quando o endpoint está ativo. |
| IAM_HTTP_HISTOGRAMS_ENABLED | false | Ativa buckets do timer HTTP para agregação futura de percentis; aumenta séries/memória. |

O token tem 32–256 caracteres sem espaços internos; espaços/quebras nas extremidades são removidos. Arquivo regular de até 512 bytes; inválido/ausente aborta startup sem imprimir caminho/valor na mensagem de validação. Inicializador cria 32 bytes CSPRNG como 64 caracteres hexadecimais e recusa sobrescrita. A aplicação carrega digest SHA-256 no startup; troca do arquivo exige reinício. Diretórios/arquivos usam permissões do sistema; o inicializador não configura ACL.

Somente GET é autorizado com METRICS_READ, sem sessão/cookies e com comparação constante do digest. Authorization ausente, incorreto, duplicado ou token administrativo recebe 401. POST com token de leitura recebe 403. O token não autentica em /api/admin/** nem /actuator/metrics; env/configprops/beans continuam sem exposição. Sem a flag, a rota é negada pela segurança padrão (403), sem bean do endpoint; não existe leitura pública de métricas. Health/readiness/liveness preservam seu contrato.

O registro Prometheus está no classpath mesmo quando a rota está desligada; desligar o endpoint não desliga toda a instrumentação Micrometer interna. Histograma HTTP usa faixa esperada de 1 ms a 10 s para limitar buckets; observações fora da faixa ainda contam no timer. Isso não impõe timeout nem rejeita requisições. A tag application é o nome fixo da aplicação.

## Inventário de métricas existentes

Nomes Micrometer são normalizados em Prometheus; contadores têm sufixo _total, timers têm _seconds_count/_seconds_sum e histogramas habilitados têm _seconds_bucket. Famílias abaixo não são séries garantidas antes de suas funcionalidades/eventos serem exercitados.

| Família no código | Forma/finalidade | Ativação e leitura |
| --- | --- | --- |
| http.server.requests | Contagem/tempo por method, uri de rota, status/outcome/exception | Tráfego HTTP; buckets opcionais. A exportação inclui o próprio scrape; excluir rotas Actuator nas análises de negócio. |
| jvm.*, process.*, system.* | Memória, GC, threads, CPU/uptime disponíveis pelos binders | Distinguir heap de memória total/RSS; não equivale a métricas de todos os containers/host. |
| hikaricp.* / jdbc.connections.* | Uso/limites dos pools, quando o binder/pool suporta | Correlacionar saturação com latência; modo Vault dinâmico tem também sinais próprios. |
| iam.outbox.pending/published/quarantined/due/leased/expired/failed/oldest_seconds | Gauges de snapshot periódico | Snapshot disponível não comprova entrega no consumidor. |
| iam.outbox.snapshot.available/timestamp | Disponibilidade e instante do snapshot | Tratar NaN/available=0 e idade do snapshot como indisponibilidade, não backlog zero. |
| iam.outbox.delivery, iam.outbox.store.errors, iam.outbox.maintenance.errors | Contadores result/operation | Publisher/erros; dependem de flags/eventos, não são taxa final de ingestão. |
| iam.audit.consumer.messages/batches/errors | Contadores result/lotes/erros | Consumer ligado; APPENDED/DUPLICATE/INVALID/CONFLICT/RETRY/LATE conforme contrato existente. |
| iam.audit.partitions.available/runs/created | Gauge/contadores | Manutenção de partições ligada; não atesta ingestão. |
| iam.transit.requests/rejected | Timer operation/outcome e contador de rejeições | Transit ativo; operation limitada a sign/encrypt/decrypt/login/keys. |
| iam.resources.enforcement | Contador operation/result | Enforcement Redis ativo; allowed/denied/unavailable e present/absent conforme operação. |
| iam.database.vault.* | Disponibilidade, leases, rotações, renovações e falhas | Modo banco dinâmico ativo; não equivale a uma visão completa do Vault. |
| iam.pam.cleanup.* e iam.pam.expiration.* | Gauges de snapshots/freshness e contadores de falhas | PAM opcional; snapshot pode ficar indisponível; não consultar banco durante scrape para fabricar valor fresco. |
| iam.enforcer.revocation.* / iam.enforcer.block-policy.* | Contadores internos messages/result e connection.errors | **No processo enforcer**, exportadas pelo listener opcional /metrics da 10B2b1; padrão sem listener. |

Não adicionar token, e-mail, subject/user ID, eventId, correlationId, sid/jti, cursor, URL crua/query ou texto de erro como tags. Templates de rota reduzem cardinalidade de IDs; o teste cobre uma rota parametrizada e query sensível sintética, não todos os meters/extensões possíveis. Não habilitar debug HTTP ou publicar scrape/log bruto sem revisão.

## Consultas de análise — base 10B2a

As expressões foram aceitas por Prometheus real no smoke 10B2a, e o datasource Grafana retornou um frame JVM. Isso não valida percentis de uma janela sustentada: análises exigem tráfego suficiente e histórico de pelo menos a janela, sem inferir capacidade do smoke.

```promql
# Taxa HTTP por rota/método, excluindo scrapes/health.
sum by (uri, method) (rate(http_server_requests_seconds_count{application="iam-core",uri!~"/actuator/.*"}[5m]))

# p95 por rota/método, somente quando buckets estão ligados.
histogram_quantile(0.95, sum by (le, uri, method) (rate(http_server_requests_seconds_bucket{application="iam-core",uri!~"/actuator/.*"}[5m])))

# Backlog observado e idade do snapshot são sinais separados.
iam_outbox_pending{application="iam-core"}
time() - iam_outbox_snapshot_timestamp{application="iam-core"}
```

Conferir nomes efetivamente exportados e available antes de automatizar alertas. Valores/ausência de séries não comprovam ausência de falha. Percentis por instância não devem ser simplesmente promediados; os exemplos usam buckets agregáveis, sem publicar percentis calculados no cliente.

## Operação, validação e fontes

[Enforcer 10B2b1](runbooks/metricas-enforcer-local.md), [evidências 10B2b1](evidencias/etapa-10b2b1.md), [Coleta/dashboard 10B2a](runbooks/coleta-iam-local.md), [evidências 10B2a](evidencias/etapa-10b2a.md), [runbook 10B1](runbooks/metricas-prometheus-local.md), [ADR-050](adr/050-exportacao-prometheus-protegida.md), [evidências](evidencias/etapa-10b1.md) e [passo a passo](GUIA-DE-IMPLEMENTACAO.md).

Contrato de exportação/histogramas conferido na [documentação Spring Boot 3.5](https://docs.spring.io/spring-boot/3.5/reference/actuator/metrics.html), com [referência Prometheus/Micrometer 1.15](https://docs.micrometer.io/micrometer/reference/1.15/implementations/prometheus). Configuração/autorização descritas acima são decisões deste projeto, verificadas pelos testes locais.

## RabbitMQ — 10B2b2a

Overlay opcional publica 127.0.0.1:15692 (porta externa configurável). Plugin nativo, GET /metrics agregado e intervalo 15 s. Endpoint de leitura sem autenticação local; o listener interno é acessível na rede Docker do broker. Não há credencial AMQP/administrativa no scraper; outros endpoints nativos por objeto não são bloqueados pela agregação.

Dashboard UID securegate-rabbitmq tem seis painéis: up, rabbitmq_queue_messages_ready, rabbitmq_queue_messages_unacked, rabbitmq_connections, rabbitmq_channels e rabbitmq_queue_consumers. Totais são do nó e incluem filas de retry/DLQ. Scrape disponível e consumidores registrados não comprovam progresso/entrega da aplicação. Não mede SLA de revogação ou capacidade.

[Operação](runbooks/metricas-rabbitmq-local.md), [ADR-053](adr/053-coleta-opcional-rabbitmq.md), [evidências](evidencias/etapa-10b2b2a.md). PostgreSQL segue na 10B2b2b; Redis segue na 10B2b2c; Vault segue na 10B2b2d; próxima implementação: logs/tracing.

## PostgreSQL — 10B2b2b

Exporter 0.19.1 fixado por digest em overlay opcional, publicação 127.0.0.1:19187 (externa configurável), senha em arquivo e login securegate_metrics/pg_monitor. Sem credencial administrativa no exporter ou Prometheus. Coletores database/stat_database/stat_bgwriter, sem tabelas/SQL/roles/settings de negócio. Coleta a cada 15 s. TLS/autenticação HTTP não configurados para esta operação local.

Dashboard UID securegate-postgres: up, pg_up, pg_stat_database_numbackends, pg_stat_database_xact_commit, pg_database_size_bytes e pg_stat_database_deadlocks (seis painéis). Up=1 com pg_up=0 é possível em erro SQL; conferir também presença dos dados/erros dos coletores. Commits incluem monitoração e estatísticas de outros bancos. Não representam throughput IAM ou capacidade.

pg_monitor permite ler estatísticas/configuração e funções amplas do cluster, inclusive informações de sessões/SQL pelas interfaces PostgreSQL; o exporter escolhido não exporta esse texto. PUBLIC não foi alterado. ACLs, não default_transaction_read_only, impedem SELECT/DML em tabelas de negócio. [Operação](runbooks/metricas-postgres-local.md), [ADR-054](adr/054-coleta-opcional-postgresql.md), [evidências](evidencias/etapa-10b2b2b.md). Redis segue na 10B2b2c; Vault segue na 10B2b2d; próxima: logs/tracing.

## Redis — 10B2b2c

redis_exporter 1.95.0-alpine fixado por digest, overlay opcional loopback 19121 e senha JSON por arquivo. Usuário securegate_metrics: -@all e INFO/PING/CLIENT SETNAME/COMMAND INFO/SLOWLOG LEN, sem padrões de chaves/canais. CONFIG/GET/SET/SCAN/EVAL/ACL/FLUSHDB/SLOWLOG GET negados. Preparador recusa ACL customizada e gera arquivo persistente que mantém senha/permissões default e AOF; aplicação exige janela de reinício.

Dashboard UID securegate-redis: up, redis_up, redis_memory_used_bytes, redis_connected_clients, redis_db_keys e redis_commands_processed_total. Conferir também redis_exporter_last_scrape_error: erro de senha pode produzir up=1 com redis_up=0. INFO keyspace fornece contagens por DB, sem ler nomes/valores. Comandos/clientes incluem coleta; não medem throughput/SLA IAM.

SLOWLOG GET é tentado e recusado intencionalmente, gerando ACL LOG e ausência das métricas do último comando lento. CONFIG e /scrape arbitrário desabilitados; não habilitar checks de chaves, CLIENT LIST, scripts ou debug. Endpoint HTTP e Redis local sem TLS; host/rede/arquivos são limites. [Operação](runbooks/metricas-redis-local.md), [ADR-055](adr/055-coleta-opcional-redis.md), [evidências](evidencias/etapa-10b2b2c.md). Vault segue na 10B2b2d; próxima: logs/tracing.

## Vault dev — 10B2b2d

Modo -dev da versão 1.21.4 força métricas anônimas no primeiro listener. Overlay opcional restringe esse listener a loopback interno e publica listener autenticado na VAULT_PORT existente. Não publica portas 18200/18201; retenção do sink 1 min, scrape 15 s, disable_hostname=true. Aplicar/remover o overlay recria Vault dev e perde suas chaves/leases/AppRoles: a stack persistente do usuário não foi reconfigurada.

Política read-only sys/metrics, token service orphan de 1h/explicit_max_ttl, não renovável, sem default/identidade de aplicação. Credencial por arquivo read-only, flag OBS_VAULT e política/token divergentes recusados pelo provisionador. Rotação requer coletores parados e Prometheus recriado; não reinicia Vault. Endpoint publicado sem token retorna 403. HTTP local sem TLS.

Dashboard UID securegate-vault: up, vault_core_unsealed e contagens dos timers vault_core_handle_request, vault_barrier_get, vault_token_create e vault_token_lookup. Tokens Vault não são JWTs OAuth; contagens incluem administração/coleta e não medem throughput ou SLA de assinatura. A [matriz de correlação](runbooks/metricas-vault-local.md) relaciona sinais IAM/enforcer às quatro dependências sem acrescentar labels de usuário/evento/chaves/payload.

[Operação](runbooks/metricas-vault-local.md), [ADR-056](adr/056-coleta-autenticada-vault-dev.md), [evidências](evidencias/etapa-10b2b2d.md). Smokes individuais são distintos de execução conjunta sustentada; logs/tracing 10B3 e medição 10C ainda pendentes.

## Logs operacionais — 10B3a

Perfil opcional structured-logs configura console/arquivo com StructuredLogFormatter nativo do Boot. Cinco campos: timestamp, level, service, event e correlationHash. Códigos finitos (9 IAM/2 enforcer, incluindo log.record), mensagens/argumentos/exceções/pares SLF4J/MDC livre omitidos. O hash é SHA-256 do correlationId limitado; não é label de métrica, identidade ou trace distribuído. Enforcer sem contexto recebe vazio. Mensagens desconhecidas usam código genérico, reduzindo diagnóstico.

A proteção vale apenas para os encoders deste perfil, não para stdout/err direto, inicialização anterior, falhas internas do logging ou configurações externas. A 10B3a não adicionou collector de logs, backend de tracing ou spans; a seção 10B3b1 abaixo registra a evolução HTTP. [Operação](runbooks/logs-estruturados-local.md), [ADR-057](adr/057-logs-operacionais-json-restritos.md), [evidências](evidencias/etapa-10b3a.md).

## Tracing HTTP entrante — 10B3b1

Perfil http-tracing: provider/bridge explícitos, somente handler servlet e três atributos finitos (método, classe de status, flag de falha). Sem URL/query/header/identidade/payload ou evento de exceção. Resource fixo service.name=iam-core. Nenhum exporter/processamento de exportação é instalado; spans concluídos não são armazenados em produção. Métricas mantêm convenções/labels regulares; exemplares automáticos OpenMetrics não foram verificados por scrape nesta entrega. Padrão sem tracer/provider; configurações genéricas de tracing/logging OTel excluídas.

W3C traceparent versão 00 validado; duplicados/malformados criam nova raiz. Baggage/tracestate/B3 não são aceitos. Sampler local padrão 0,1, independente do sampled remoto; não é quota. JSON IAM agora tem sete campos (traceId/spanId adicionais, vazios fora do contexto); enforcer mantém cinco. IDs não são autoridade nem label de métrica. A seção seguinte registra a 10B3b2; 10B3c, backend/retensão e medição continuam pendentes. [Operação](runbooks/tracing-http-local.md), [ADR-058](adr/058-tracing-http-entrante-restrito.md), [evidências](evidencias/etapa-10b3b1.md).

## Clientes HTTP Vault — 10B3b2

Transporte síncrono compartilhado para Transit/MFA, banco dinâmico e PAM. Com tracing ativo, somente origem e rotas conhecidas recebem traceparent; headers de contexto anteriores são removidos da cópia. Sem tracer, requisição original preservada. Spans CLIENT vault.http com vault.operation, http.method, http.status_class e http.failure; sem URI/token/body/credenciais/erro livre. Raízes quando não há parent, sem contexto retido em tokens/leases ou propagação de scheduler. Sem novas métricas/labels/exporter. KV pré-Spring permanece sem tracing; 10B3c e backend/retensão pendentes. [ADR-059](adr/059-tracing-clientes-http-vault.md), [operação](runbooks/tracing-http-local.md), [evidências](evidencias/etapa-10b3b2.md).

# ADR-028 — Base de ingestão de auditoria particionada

- Estado: aceita para implementação local na 8A1.
- Data: 2026-10-19.

## Separar a projeção de ingestão da auditoria produtora

A V13 acrescenta `iam_audit.event_log` e `event_receipts`. Não converte nem move `security_events`: essa auditoria já participa das transações dos produtores e é referenciada pela Outbox. Mantê-la preserva os dados e as garantias existentes. A nova tabela receberá os envelopes originais do barramento, sem produzir outra Outbox ou ciclo de auditoria.

A 8A foi dividida em **8A1**, schema, deduplicação e manutenção, e **8A2**, identidades/permissões operacionais e de ingestão. Consumidor em lote fica na 8B; consultas por cursor, retenção/arquivamento e medição ficam nas entregas seguintes. A 8A1 não liga consumidor ou novo endpoint.

## Modelo e deduplicação

`event_log` é particionada mensalmente por `occurred_at`, com chave primária `(id, occurred_at)`, envelope JSONB original, metadados extraídos e horário de recebimento separado. Índices de tempo/id, recurso/tempo/id e tipo/tempo/id preparam consultas posteriores, sem afirmar plano ou desempenho já medidos.

Uma chave composta de tabela particionada não assegura unicidade global de `id`. `event_receipts` mantém `id` como chave primária não particionada, instante e SHA-256 do `payload::text` JSONB. A FK composta liga o registro mensal ao recibo. SHA-256 usa função nativa PostgreSQL, sem instalar extensão.

Um trigger BEFORE INSERT, clonado nas partições, tenta inserir o recibo. Se já existir, confere instante e fingerprint: replay canônico retorna zero linhas inseridas; conflito usa SQLSTATE 23505. O fingerprint inclui todo o envelope, mas não `received_at`. JSONB normaliza ordem/espaços das chaves; não normaliza representações textuais diferentes de um timestamp dentro do JSON. Reusar eventId com envelope diferente é conflito, mesmo que a diferença pareça semanticamente equivalente ao cliente.

`JdbcAuditEventStore` implementa a porta `AuditEventStore` com APPENDED/DUPLICATE. A inserção extrai timestamp/metadados no PostgreSQL do mesmo JSONB serializado, evitando divergência entre parâmetros de timestamp e payload. PostgreSQL guarda timestamps em microssegundos; o texto original permanece no envelope. Os produtores existentes já emitem instantes truncados a microssegundos.

Recibo e linha mensal participam da mesma transação, incluindo concorrência, erro de constraint e rollback externo. Não existe reserva de eventId confirmada antes do registro. As verificações de concorrência usam o isolamento padrão READ COMMITTED. Falhas SQL continuam propagadas; classificação de retry/DLQ será integrada com o consumidor.

## Partições e operação

A migração cria o mês anterior, atual e três futuros, em UTC. Não há DEFAULT: mês não provisionado falha sem persistir recibo. `ensure_event_partitions(date, count)` cria de 1 a 24 meses por chamada, usa identificadores derivados de datas, serializa DDL por advisory lock transacional e recusa relações/bounds inesperados. Pode provisionar histórico por operação autorizada, sem apagar tabelas existentes.

A função usa permissões do chamador e não é executável por PUBLIC. `AuditPartitionMaintenance` é opcional (`-EnableAuditPartitions`), cria mês anterior/atual e horizonte configurado de 1–12 meses futuros, com polling padrão de uma hora. Falhas não desativam ticks posteriores. Métricas leem estado em memória, sem SQL no scrape. DDL pode bloquear ingestão; identidade, timeouts e privilégios operacionais serão tratados na 8A2.

## Imutabilidade e limites

UPDATE/DELETE são recusados por triggers no registro e nos recibos; TRUNCATE é recusado também em cada partição, pois triggers de statement não são herdados. A função de registro usa SECURITY DEFINER com search_path fixo e referências qualificadas; não expõe parâmetros para DDL. Sua execução por PUBLIC é revogada.

Isso não substitui separação de privilégios: a conexão atual ainda executa Flyway e é proprietária dos objetos. Proprietário/superusuário pode alterar DDL ou desabilitar triggers. A 8A2 deve limitar a identidade de ingestão antes de conectar o consumidor, preservando o registro de recibos pelo trigger sem conceder escrita direta nele. A 8A1 não cria roles globais ou segredos novos.

Recibos não têm expurgo automático. Arquivar uma partição não deve apagar a identidade global, mas replay para mês sem partição anexada ainda falha antes do trigger; não se promete ACK de arquivo histórico nesta entrega. Retenção/detach/restore exigem política posterior. Crescimento da tabela global, backfill, consultas e ingestão com milhões de registros ainda serão medidos.

Referências: [guia](../runbooks/auditoria-particionada-local.md), [evidências](../evidencias/etapa-08a1.md) e [plano](../PLANO-DE-IMPLEMENTACAO.md).

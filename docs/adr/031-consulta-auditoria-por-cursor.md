# ADR-031 — Consulta de auditoria por cursor

- Data: 19/10/2026.
- Estado: aceita; implementação local da 8C.
- Base: [ADR-028](028-base-auditoria-particionada.md), [ADR-029](029-identidades-auditoria.md) e [ADR-030](030-consumidor-auditoria-em-lote.md).

## Decisão

Disponibilizar `GET /api/admin/audit/events` somente quando `iam.audit.query.enabled=true`. A rota reutiliza a proteção administrativa existente: bootstrap opt-in, sem sessão, acesso anônimo negado e respostas `Cache-Control: no-store`. O token administrativo temporário continua sendo o mecanismo dos outros endpoints administrativos; JWT de usuário não equivale a permissão para consultar auditoria. Não há busca pública, escrita ou exportação em massa nesta API.

Exigir `from` inclusivo e `to` exclusivo, em UTC/ISO-8601, precisão máxima de microssegundos, entre 1970 e o início de 9998, com janela de até 31 dias. A janela limita partições candidatas. Filtros opcionais exatos por `eventType`, `resource`, `actor` e `correlationId`; combinação por AND. Limite padrão 20, entre 1 e 100. Parâmetros desconhecidos/repetidos, filtros vazios e cursor inválido são recusados. Não há OFFSET, ordenação escolhida pelo cliente, LIKE ou SQL recebido na requisição.

Usar keyset `ORDER BY occurred_at DESC, id DESC`, com corte estrito `(occurred_at,id)<(cursor_time,cursor_id)`. Buscar `limit+1` registros numa única consulta, sem COUNT e sem busca adicional por linha. O item adicional determina `nextCursor`. O cursor usa as colunas reais da chave PostgreSQL, não o timestamp do payload original: o envelope pode preservar nanossegundos e o banco armazena microssegundos.

O cursor é Base64URL sem padding, versionado e limitado a 256 caracteres, com posição e fingerprint SHA-256 da janela/filtros. Os mesmos filtros devem acompanhar a continuação; o limite pode mudar dentro dos limites permitidos. O fingerprint evita reutilização acidental entre consultas, mas **não é assinatura**. Um administrador pode construir uma posição válida; isso não concede autorização adicional e os filtros continuam parametrizados/aplicados pelo servidor. O cursor não contém credencial e não é um controle de acesso.

## Banco e índices

Reutilizar o pool restrito `auditIngestionConnection`, já autorizado a SELECT no parent. Não há fallback para DataSource proprietário. Cada consulta abre transação nova, explicitamente `SET TRANSACTION READ ONLY` pelo `DataSourceTransactionManager`, com timeout de cinco segundos, além dos limites de pool/driver/lock da 8A2. A configuração usa um transaction manager separado sobre o mesmo pool; a ingestão continua podendo INSERT depois da consulta. O login mantém INSERT/SELECT: esta entrega não cria um login exclusivamente leitor nem isola o processo inteiro da credencial principal.

A V15 adiciona `(actor,occurred_at DESC,id DESC)` e `(correlation_id,occurred_at DESC,id DESC)` no parent; os índices de tempo, recurso e tipo já existem na V13. PostgreSQL replica índices nas partições atuais/futuras. DDL transacional com CREATE INDEX normal pode bloquear escritas durante upgrade: planejar a migração em ambiente com dados. Não é uma migração online com CONCURRENTLY.

Filtros e posições são parâmetros preparados. Fragmentos SQL e colunas são constantes internas. A resposta inclui ID/occurredAt do banco, receivedAt e o envelope completo autorizado. Não se consulta auditoria legada nem Outbox. O payload possui metadados de usuários e requer os mesmos cuidados de acesso/armazenamento da auditoria.

## Consistência e limites

- A chave total desempata timestamps iguais sem repetir ou pular registros já existentes por efeito do empate.
- Cada página é uma leitura nova. Não há snapshot entre requisições: novas linhas acima do cursor ficam fora da continuação; chegadas tardias abaixo dele podem aparecer. Recomeçar a janela é necessário para revisar inserções retroativas. Retenção/arquivamento concorrente fica para 8D.
- Falha de banco/lock/timeout retorna 503 sanitizado, sem SQL, filtros, corpo de evento ou credenciais. Validação inválida retorna 400. Não há fallback para legado/dados em memória.
- Consulta divide o pool de duas conexões com ingestão; pode disputar capacidade. Uma página pode conter até 100 envelopes. Não há promessa de vazão/latência, nenhum teste de milhões de linhas e nenhuma garantia de plano ideal para toda combinação. Medições e ajuste de índices ficam em 8D.
- O endpoint permanece desabilitado por padrão. A ativação via script exige credenciais locais provisionadas na 8A2. Autorização administrativa definitiva e hardening continuam no roteiro.

Operação: [runbook](../runbooks/consulta-auditoria-local.md). Validação: [evidências](../evidencias/etapa-08c.md).

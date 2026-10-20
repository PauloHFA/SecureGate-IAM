# ADR-030 — Consumidor de auditoria em lote

- Data: 19/10/2026.
- Estado: aceita; implementação local da 8B.
- Base: [ADR-028](028-base-auditoria-particionada.md) e [ADR-029](029-identidades-auditoria.md).

## Decisão

O `iam-core` pode ativar um worker opcional e independente do publicador Outbox. Ele faz `basicGet` manual em `audit.persist`, com até 100 mensagens por tick (padrão 20), em um único canal/thread. A coleta não espera completar o lote. Intervalo entre ticks de 100–60000 ms, padrão 1000 ms. Não há callback concorrente nem ACK automático.

O decoder valida contrato v1, cabeçalhos de identidade/tipo/produtor/correlação/versão, persistência, content type, encoding e timestamp com precisão de segundos. Rejeita campos desconhecidos, chaves repetidas, tokens JSON adicionais e envelopes inválidos. Corpo máximo de 65536 bytes e representação compacta do envelope até 65000 bytes, reservando espaço para separadores do JSONB. Conteúdos e credenciais não são registrados em logs/métricas.

`AuditEventStore.appendBatch` usa JDBC batch em uma única transação no pool dedicado de ingestão. Recibos e linhas particionadas confirmam juntos. Resultados APPENDED/DUPLICATE só retornam depois do commit no caminho do worker. O consumidor envia ACK individual após esse retorno; nenhuma escrita na auditoria produtora ou Outbox é feita.

Falha `DuplicateKeyException` reverte todo o lote. O worker reapresenta cada item em sua própria transação: conflito de identidade vai para DLQ, replay canônico recebe ACK e evento novo confirma normalmente. Isso permite progresso dos vizinhos sem descartar um lote inteiro. O primeiro envelope aceito para um ID permanece como referência; conteúdo conflitante nunca o substitui.

Outras falhas de persistência, incluindo falta de partição, lock/statement timeout, deadlock ou banco indisponível, provocam NACK/requeue dos itens pendentes. Não há loop interno imediato de retry: o próximo tick aguarda o intervalo configurado. Falha no canal/conexão fecha a conexão e deixa o broker devolver as mensagens ainda não confirmadas. Commit incerto ou ACK perdido são resolvidos por deduplicação global na entrega seguinte. Não existe transação distribuída PostgreSQL/RabbitMQ.

## Configuração e operação

`iam.audit.consumer.enabled` é false por padrão. Exige `AuditEventStore` habilitado por `iam.audit.ingestion.enabled`, com login dedicado/arquivo de credencial válidos. Sem isso, o startup falha; não há fallback para a conexão proprietária. `Start-IamCore.ps1 -EnableAuditConsumer` habilita ingestão automaticamente e fornece as credenciais RabbitMQ locais. O publicador e a manutenção de partições continuam opcionais e independentes.

Métricas de baixa cardinalidade: `iam.audit.consumer.messages{result=APPENDED|DUPLICATE|INVALID|CONFLICT|RETRY}`, `iam.audit.consumer.batches` e `iam.audit.consumer.errors`. Contadores de mensagem são atualizados após o comando AMQP correspondente; não são um ledger durável nem prova da recepção do ACK pelo broker. Batches contam apenas o caminho de lote confirmado e não o isolamento de conflito.

## Consequências e limites

- Há entrega pelo menos uma vez e efeito deduplicado no banco; não se promete transporte exatamente uma vez.
- O batch reduz commits, mas não é COPY nem prova de vazão sob carga. Deadlocks concorrentes são recuperáveis, sem garantia de ordenação global.
- Falhas persistentes de banco/partição mantêm o lote para retry e podem atrasar mensagens seguintes. Corrigir a causa operacional; não enviar automaticamente falha de infraestrutura para DLQ.
- Requeue usa a própria fila e o intervalo do worker; as filas `.retry` existentes não são utilizadas por este consumidor. Não há expiração automática do número de tentativas.
- As filas locais existentes são duráveis clássicas. O encaminhamento DLX segue as garantias dessa topologia; este trabalho não comprova retenção de DLQ diante de falha de cluster. Não há replay automático de DLQ.
- Limites de pool/driver/statement da 8A2 são por operação, não prazo absoluto para um lote inteiro. Shutdown interrompe a coleta e encerra conexão após o tick em andamento.
- A conexão principal IAM continua proprietária, conforme 8A2. O usuário RabbitMQ local é compartilhado com o publicador; credenciais/permissões por consumidor em produção ficam no hardening.
- Consulta por cursor, retenção, milhões de registros e migração da auditoria legada permanecem nas próximas entregas.

Operação: [runbook](../runbooks/consumidor-auditoria-local.md). Validação: [evidências](../evidencias/etapa-08b.md).

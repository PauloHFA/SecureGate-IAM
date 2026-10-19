# ADR-021 — Operação, métricas e retenção da Outbox

- Estado: aceita para implementação local na 6C.
- Data: 2026-10-19.
- Complementa a [ADR-020](020-publicacao-outbox-rabbitmq.md).

## Decisão

Eventos publicados podem ser removidos da Outbox após sete dias de `published_at`, em lotes de 100 por ciclo, somente com retenção explicitamente habilitada. O prazo é contado desde a confirmação persistida, não desde a criação. O limite configurável é de 1–3650 dias e 1–1000 linhas por ciclo. A seleção usa índice parcial, transação curta com timeout de cinco segundos e `FOR UPDATE SKIP LOCKED`. Não há laço que tente esvaziar a tabela no mesmo ciclo.

A limpeza não remove pendências, quarentenas, auditorias em `security_events` ou histórico em `outbox_operations`. A Outbox é transporte; retenção/arquivamento da auditoria é uma decisão posterior. ACK confirma aceitação do broker, não processamento pelo consumidor: sua recuperação após limpeza depende da durabilidade das filas e da política futura de replay/arquivamento. Sete dias é um padrão local configurável, não uma política de conformidade.

`INVALID_EVENT` encerra as tentativas automáticas daquele envelope e preenche `quarantined_at`. Falhas transitórias e de roteamento continuam com o backoff da 6B. Falhas persistentes de qualquer classe podem ser colocadas em quarentena pelo operador. Não há descarte por número de tentativas.

A função SQL `iam_audit.set_outbox_quarantine(UUID, BOOLEAN)` permite quarentena/liberação de uma pendência por operador confiável do banco. Cada transição registra eventId, horário, usuário de sessão do banco, ação e motivo fechado, em histórico protegido contra UPDATE/DELETE por trigger. A função usa os privilégios do chamador e não é executável por PUBLIC. Não há endpoint HTTP de mutação operacional.

Quarentena limpa o lease e seu token, impedindo confirmação posterior pelo antigo worker. Isso não cancela uma mensagem já em trânsito: ela pode chegar ao broker. Liberação mantém payload e eventId, limpa a última falha e torna o evento imediatamente elegível. Publicados não podem ser reabertos pela função. O payload continua imutável; envelope inválido exige correção do leitor/compatibilidade ou um novo fato correto, nunca edição do registro antigo para ocultar o incidente.

## Observabilidade

Um worker independente coleta um retrato agregado a cada 30 segundos, mesmo com publicação desabilitada. Consultar métricas não executa SQL. Até a primeira coleta os valores são NaN; falha preserva o último retrato, marca `snapshot.available=0` e mantém o horário da última coleta bem-sucedida. A idade do evento mais antigo é calculada no instante do retrato. Falhas de coleta e de retenção são contadas separadamente e não cancelam os próximos ciclos.

Há gauges de pendentes, publicados retidos, quarentena, elegíveis, leases ativos/vencidos, última falha e idade da pendência mais antiga; contadores de resultado da entrega, erro de persistência, expurgo e erro de manutenção. Tags têm conjunto limitado: nenhuma identidade de usuário, eventId, tipo arbitrário, payload ou mensagem de exceção. A consulta agregada percorre a Outbox: grandes volumes exigem avaliar custo/intervalo e retenção antes de escalar.

`GET /actuator/metrics` e seus detalhes usam a autenticação administrativa bootstrap já existente, desabilitada por padrão. As sondas de saúde continuam públicas sem detalhes. Não se adiciona dependência do broker à readiness: a Outbox permite aceitar operações enquanto o broker está indisponível, desde que o PostgreSQL esteja disponível. Alarmes por backlog e espaço em disco precisam ser configurados pelo operador; não são alertas externos automáticos.

## Limites

As garantias contra alteração operam para o fluxo normal com triggers ativos. O proprietário/superusuário do banco pode alterar DDL ou desativar proteção; o usuário compartilhado da stack local não identifica uma pessoa. Separação de papéis de banco, armazenamento imutável externo, Prometheus/tracing e produção com TLS permanecem nas entregas de segurança/observabilidade. A etapa 7 implementará os consumidores e a idempotência por eventId.

Procedimentos: [operação local](../runbooks/operacao-outbox-local.md). Validação: [evidências 6C](../evidencias/etapa-06c.md).

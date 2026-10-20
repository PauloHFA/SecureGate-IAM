# Evidências — 8B: consumidor de auditoria em lote

- Data: 19/10/2026.
- Branch: `feat/audit-batch-consumer`, base `155c2c1` (8A2).
- Commit/publicação e CI remota: pendentes.

## Escopo e casos

Consumidor opcional `audit.persist`, JDBC batch atômico no pool de ingestão, ACK posterior ao commit, isolamento de conflito e recuperação com requeue/reconexão. Topologia e migrações V13/V14 preservadas. Nenhum segredo real ou alteração na stack persistente é necessário para os testes.

Testes exercitam PostgreSQL 16.15 e RabbitMQ 4.3.6 reais, descartáveis, com login restrito provisionado pela SQL da 8A2:

- Lote com duas mensagens e replay: duas linhas, dois recibos, APPENDED/DUPLICATE e nenhuma nova Outbox.
- Conflito de identidade reverte o lote completo, inclusive recibo do vizinho; isolamento confirma os válidos e rejeita só o conflito para DLQ.
- Payload/cabeçalho inválidos vão para DLQ; mensagem válida adjacente confirma.
- Falta de partição reverte todo o lote e preserva mensagens. Preparar o mês como administrador permite recuperação no mesmo worker.
- Lock real no parent aciona timeout, não envia ACK e recupera após liberação.
- Abortar a conexão RabbitMQ após commit, antes do ACK, provoca replay classificado DUPLICATE, sem segunda linha/recibo.
- 21 mensagens, limite 20: primeiro tick deixa uma na fila e segundo termina.
- Testes unitários verificam ordem store/ACK, NACK de todo lote, isolamento com falha transitória, reconexão após ACK incerto e validação estrita do JSON/cabeçalhos/limites.
- Configuração desabilitada por padrão, ausência de store impede startup e limites inválidos não são aceitos.

## Validação

`mvnw.cmd -B -ntp clean verify` aprovado com Java 21: **573 testes, zero falhas, zero erros e zero ignorados**, incluindo os oito casos ponta a ponta anteriores. Concluído às 23:11:46 de 19/10/2026 (UTC−03), em 5 minutos e 28 segundos. Spotless, ArchUnit e cobertura mínima JaCoCo de 80% nos pacotes configurados dos dois serviços aprovados.

Build em cópia isolada, com 318 arquivos de código/configuração/scripts idênticos por SHA-256. JARs atualizados nos targets locais. Log em `.firecrawl/etapa8b-verify.log`; relatórios em `.firecrawl/etapa8b-reports/`, ignorados pelo Git. Scripts PowerShell sem erros de parsing, 559 links Markdown locais válidos, `git diff --check` aprovado e nenhuma correspondência com credenciais locais nos arquivos alterados. O teste focado inicial também passou.

## Limites

Sem commit/push/CI remota desta branch. Não foi ativado consumidor contra a stack persistente. Consulta por cursor (8C), retenção/carga (8D), alta disponibilidade e migração de legado não são concluídas nesta etapa. Identidade principal proprietária e garantias locais de DLX permanecem conforme ADR-029/030. Não se declara vazão, latência sob carga ou transporte exatamente uma vez.

Referências: [ADR-030](../adr/030-consumidor-auditoria-em-lote.md), [runbook](../runbooks/consumidor-auditoria-local.md) e [plano](../PLANO-DE-IMPLEMENTACAO.md).

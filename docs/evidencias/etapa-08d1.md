# Evidências — 8D1: arquivo mensal de auditoria

- Data: 19/10/2026.
- Branch: `feat/audit-monthly-archive`, base local `7962347` (8C).
- Commit/push/CI remota desta entrega: pendentes.

## Escopo e cenários

V16 aditiva com arquivo no mesmo PostgreSQL, catálogo imutável, operação administrativa por mês e retenção quente de calendário. Nenhuma operação de arquivo é executada pela migração ou pelo startup. Script usa plano por padrão e `-Apply` explícito. Recibos globais preservados e consumidor distingue replay, conflito e novo evento tardio.

Testes reais PostgreSQL/RabbitMQ verificam:

- Detach/mudança de schema preservam dados/recibos; arquivo e catálogo recusam mutações e a manutenção não recria mês arquivado.
- Repetição retorna a mesma contagem; rollback externo restaura parent e permite ingestão naquele mês.
- Mês quente, parâmetros inválidos, relação ausente/inesperada são recusados.
- Lock real provoca timeout sem deixar catálogo/arquivo parcial; nova tentativa funciona após liberação.
- Identidades runtime não arquivam, não leem arquivo/catalogam nem acessam recibos diretamente.
- Replay arquivado retorna DUPLICATE, conflito conserva 23505 e novo evento tardio resulta em exceção específica; erro de lote reverte os vizinhos.
- Consumidor RabbitMQ confirma replay e eventos válidos, rejeita apenas conflito/LATE à DLQ, sem loops de retry ou criação de recibo para evento tardio.
- SQL psql extraído do script operacional comprova que plano não move dados e aplicação explícita é repetível em contêiner descartável.
- Replay concorrente ao commit de arquivo permanece deduplicado; testes unitários cobrem isolamento de evento LATE sem bloquear vizinho válido.

## Validação

`mvnw.cmd -B -ntp clean verify` aprovado com Java 21, PostgreSQL 16.15, RabbitMQ 4.3.6, Redis 7.4.11 e Vault 1.21.4 temporários: **598 testes, zero falhas, zero erros e zero ignorados**, incluindo os oito casos ponta a ponta anteriores. Concluído às 23:51:57 de 19/10/2026 (UTC−03), em 5 minutos e 37 segundos. Spotless, ArchUnit e cobertura mínima JaCoCo de 80% nos pacotes configurados dos dois serviços aprovados.

Build em cópia isolada com 335 arquivos de código/configuração/scripts idênticos por SHA-256. JARs atualizados nos targets locais. Log `.firecrawl/etapa8d1-verify.log`; relatórios e logs de processos em `.firecrawl/etapa8d1-reports/`, ignorados pelo Git. Scripts PowerShell sem erros de parsing, 598 links Markdown locais válidos, `git diff --check` aprovado e nenhuma correspondência com credenciais locais nas alterações. Testes focados iniciais aprovados; regressão verificou upgrade V5→V16, reexecução Flyway, privilégios, consultas e imutabilidade.

## Limites

Nenhum arquivamento/provisionamento foi aplicado contra a stack persistente. Sem purge, liberação de disco, backup externo/WORM ou restore automatizado. Consulta HTTP continua somente no log quente. Proprietário/superusuário pode alterar DDL. DDL bloqueante e contagem por partição exigem janela operacional; desempenho com milhões de registros e planos/N+1 ficam na 8D2.

Referências: [ADR-032](../adr/032-arquivamento-mensal-auditoria.md), [guia](../runbooks/arquivamento-auditoria-local.md) e [plano](../PLANO-DE-IMPLEMENTACAO.md).

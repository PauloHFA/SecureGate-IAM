# Evidências — 8C: consulta de auditoria por cursor

- Data: 19/10/2026.
- Branch: `feat/audit-cursor-query`, base publicada `f005334` (8B).
- Commit/publicação/CI remota desta etapa: pendentes.

## Escopo e casos

API administrativa opcional com keyset `(occurred_at,id)`, filtros exatos, janela limitada, pool de auditoria e transações somente leitura. V15 adiciona índices de ator/correlação e preserva V13/V14, imutabilidade, recibos e consumidor.

Testes com PostgreSQL real verificam:

- Percurso de sete IDs em timestamps iguais, incluindo UUIDs com bits altos/baixos, sem lacunas/duplicatas e em ordem PostgreSQL.
- Cursor baseado em timestamp armazenado em microssegundos, preservando nanossegundos do envelope sem quebrar continuação.
- Fronteira mensal UTC com `from` inclusivo e `to` exclusivo.
- Combinação exata de tipo/recurso/ator/correlação; entrada semelhante a SQL permanece valor parametrizado.
- Índices válidos no parent e índices de ator/correlação herdados nas partições existentes e em novo mês criado após V15.
- Inserção acima do cursor não repete páginas; chegada tardia abaixo pode aparecer, documentando ausência de snapshot.
- HTTP real: acesso administrativo obrigatório, `no-store`, filtro/cursor inválido ou alterado/duplicado/desconhecido retorna 400 sem eco de entrada sensível.
- Lock real gera 503 sanitizado; transação somente leitura rejeita INSERT com SQLSTATE 25006 e o pool volta a permitir ingestão após a consulta.
- Testes unitários cobrem validação de limites/filtros, cursores malformados e fora de janela, binding de filtros, tamanho de página variável, página vazia/final e falhas sanitizadas.
- Configuração desabilitada por padrão; ativação sem pool dedicado falha, sem fallback para o DataSource proprietário.

## Validação

`mvnw.cmd -B -ntp clean verify` aprovado com Java 21, PostgreSQL 16.15, RabbitMQ 4.3.6, Redis 7.4.11 e Vault 1.21.4 temporários: **588 testes, zero falhas, zero erros e zero ignorados**, incluindo os oito casos ponta a ponta anteriores. Concluído às 23:33:58 de 19/10/2026 (UTC−03), em 5 minutos e 30 segundos. Spotless, ArchUnit e cobertura mínima JaCoCo de 80% nos pacotes configurados dos dois serviços aprovados.

Build em cópia isolada, com 331 arquivos de código/configuração/scripts idênticos por SHA-256. JARs atualizados nos targets locais. Log `.firecrawl/etapa8c-verify.log`; relatórios e logs de processos em `.firecrawl/etapa8c-reports/`, ignorados pelo Git. Script e exemplos PowerShell sem erros de parsing; 577 links Markdown locais válidos, `git diff --check` aprovado e nenhuma correspondência com credenciais locais nos arquivos alterados. Teste focado inicial aprovado. A regressão validou upgrade V5→V15, reexecução sem novas migrações, consumidor, privilégios, manutenção e imutabilidade.

## Limites

Não foi ativada consulta na stack persistente. Sem commit/push/CI remota desta etapa. A API usa o bootstrap administrativo temporário existente; o login de auditoria mantém INSERT/SELECT, com leitura explicitamente read-only por transação. Conexão principal proprietária preservada. Não há snapshot entre páginas, exportação, pesquisa no legado, retenção, prova de plano ideal ou medições com milhões de registros (8D).

Referências: [ADR-031](../adr/031-consulta-auditoria-por-cursor.md), [guia](../runbooks/consulta-auditoria-local.md) e [plano](../PLANO-DE-IMPLEMENTACAO.md).

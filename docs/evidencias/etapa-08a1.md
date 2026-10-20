# Evidências — 8A1: base da auditoria particionada

- Data: 2026-10-19.
- Branch: `feat/partitioned-audit-foundation`, base `9be334b` (7D2 local).
- Escopo: V13 aditiva, ingestão mensal, recibos globais e manutenção opcional de partições.
- Entrega local; publicação e CI remota pendentes.

## Validação

`mvnw.cmd -B -ntp clean verify` aprovado com Java 21, PostgreSQL 16.15, RabbitMQ 4.3.6, Redis 7.4.11 e Vault 1.21.4 temporários: **549 testes, zero falhas, zero erros e zero ignorados**. Build concluído em 4 minutos e 54 segundos, às 22:14:56 de 2026-10-19 (UTC−03). Spotless, regras de arquitetura e cobertura mínima JaCoCo de 80% dos pacotes configurados aprovados nos dois serviços.

Build em cópia isolada com 305 arquivos relevantes de código/configuração/scripts idênticos por SHA-256. JARs preservados nos targets dos serviços; log `.firecrawl/etapa8a1-verify.log`; relatórios e logs locais dos processos em `.firecrawl/etapa8a1-reports/`. Artefatos são ignorados pelo Git. Links locais, exemplos e script PowerShell e `git diff --check` conferidos; nenhuma correspondência com credenciais locais nos arquivos alterados. A regressão completa inclui os oito testes ponta a ponta anteriores, com nove medições saudáveis e os cinco cenários de resiliência.

## Casos exercitados

Onze testes de integração PostgreSQL e três de configuração/manutenção foram acrescentados:

- Upgrade V12 → V13 com registro legado e Outbox pendente: ambos preservados, FK intacta, sem migração automática para o novo log; reaplicação Flyway executa zero migrações.
- Virada de mês em UTC, incluindo último microssegundo, independentemente do fuso da conexão; roteamento para a partição correta e chave `(id, occurred_at)` conferidos no catálogo.
- Replay idêntico retorna DUPLICATE com uma linha/recibo. Mesmo eventId com outro payload ou mês é recusado, preservando a primeira gravação.
- Cinco rodadas de oito transações concorrentes por evento: em cada rodada, uma APPENDED e sete DUPLICATE, com um recibo e uma linha. O tratamento cobre ambos os índices únicos do recibo, sem erro espúrio no replay concorrente.
- Dois payloads concorrentes com o mesmo eventId não podem confirmar ambos.
- Partição ausente não deixa recibo órfão; provisionar o mês permite reapresentar o mesmo evento.
- Rollback de transação externa desfaz log/recibo juntos; erro de limite de tamanho do envelope também não reserva o eventId.
- UPDATE/DELETE/TRUNCATE recusados no parent, em partição direta e nos recibos, mantendo os dados.
- Manutenção concorrente/idempotente atravessa a virada do ano sem duplicar tabelas; parâmetros inválidos e relação com nome incompatível são recusados.
- Clock controlado cria mês anterior, atual e três futuros; rodar novamente não cria tabelas extras.
- Modo desabilitado não consulta DDL; modo habilitado agenda a manutenção. Horizonte/poll inválidos impedem configuração. Falha de manutenção não desativa o tick seguinte; scrape só lê métricas em memória.

Os testes usam banco descartável. Nenhuma stack persistente, credencial local ou auditoria existente foi alterada. As propriedades/versões esperadas dos testes antigos foram atualizadas para V13, incluindo upgrade V5 → V13.

## Aceite e limites

A 8A1 entrega a fundação funcional local. A **8A2** ainda precisa separar identidade/permissões de ingestão e manutenção, com timeouts operacionais. A conexão atual continua proprietária dos objetos e executa Flyway: triggers não impedem esse proprietário de alterar DDL. O consumidor em lote/recuperação fica na 8B; não há ingestão automática de `audit.persist`, backfill, cursor público, retenção/arquivamento ou benchmark de milhões de eventos nesta entrega.

`security_events`/Outbox permanecem no caminho produtor. `event_log` começa separado e vazio; recibos globais são permanentes até haver política segura de retenção. Sem partição anexada, inclusive no replay histórico, a inserção falha antes do trigger; não se promete ACK de arquivo histórico. A criação de partições pode adquirir locks e deve ser operada antes da necessidade de ingestão do mês.

O modo opcional é ativado por `Start-IamCore.ps1 -EnableAuditPartitions`; o switch não liga OAuth, publicador ou consumidor. O worker usa UTC, horizonte/frequência limitados e métricas sem SQL no scrape. Não há segredo/dependência novos.

Referências: [ADR-028](../adr/028-base-auditoria-particionada.md), [guia](../runbooks/auditoria-particionada-local.md) e [plano](../PLANO-DE-IMPLEMENTACAO.md).
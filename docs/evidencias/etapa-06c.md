# Evidências — 6C: operação da Outbox

- Data: 2026-10-19.
- Branch: `feat/outbox-operations`, base `08dd420` (6B).
- Escopo: métricas, retenção opcional, quarentena e recuperação operacional.
- Commit, publicação e CI remota: pendentes.

## Validação

`mvnw.cmd -B -ntp clean verify` aprovado com Java 21, PostgreSQL 16.15, RabbitMQ 4.3.6 e Vault 1.21.4 temporários: **472 testes, zero falhas, zero erros e zero ignorados**. Build final concluído em 2 minutos e 42 segundos, às 13:10:52 de 2026-10-19 (UTC−03). Spotless, arquitetura e limites de cobertura JaCoCo aprovados, sem reduzir o mínimo de 80% dos pacotes configurados.

Build executado em cópia isolada para evitar interferência da IDE em `target`. Os 261 arquivos relevantes de código/configuração/scripts coincidem por SHA-256 com as fontes validadas. JAR preservado em `iam-core/target/iam-core-0.1.0-SNAPSHOT.jar`; log em `.firecrawl/etapa6c-verify.log`; relatórios em `.firecrawl/etapa6c-reports/`, ignorados pelo Git.

A entrega acrescenta quatro testes unitários e seis testes de integração. Expectativas de migração foram atualizadas para V12 e a regressão de inicialização verifica bloqueio das métricas com bootstrap desligado. A suíte real de publicação da 6B continua aprovada com 12 casos, incluindo interrupção/recuperação do RabbitMQ, concorrência e duplicata após perda simulada da marcação no banco.

## Casos cobertos

- Limpeza limitada pelo tamanho do lote e idade de publicação, preservando auditoria, histórico operacional, publicações recentes, pendências e quarentenas.
- Seleção de limpeza ignora candidato bloqueado por outra transação. Falha injetada após DELETE desfaz a exclusão; recuperação permite removê-lo em nova execução.
- Retenção permanece desligada no contexto Spring padrão. Worker explicitamente habilitado remove somente publicações elegíveis.
- `INVALID_EVENT` coloca a pendência em quarentena, registra motivo e impede novas reivindicações. Liberação retorna exatamente o mesmo eventId e payload.
- Quarentena operacional invalida lease ativo; o trabalhador antigo não pode confirmar nem reagendar. A função não reabre publicação concluída e recusa argumentos nulos.
- Histórico de transições recusa DELETE, e o payload continua protegido contra UPDATE. O histórico conserva duas transições após quarentena/liberação.
- Métricas agregadas refletem pendências elegíveis, em lease, em retry e em quarentena. O endpoint exige token administrativo, recusa acesso ausente/inválido e não inclui eventId/token nos dados agregados.
- Coleta inicial sem valores expõe NaN; falha simulada preserva valores/horário e sinaliza indisponibilidade; ciclo posterior restaura disponibilidade.
- Falha simulada na retenção não impede coleta nem próxima limpeza. Mensagem sensível da exceção não aparece no log do worker.
- Contadores distinguem publicação marcada, lease perdido, retry e falhas de banco. Configuração recusa trabalho sem limites válidos.

As falhas de snapshot são injetadas em teste unitário; não se declara teste dedicado de queda física do PostgreSQL nesta entrega. O teste de rollback usa erro real de trigger no PostgreSQL temporário. Os testes de recuperação do broker usam RabbitMQ real da suíte 6B.

## Aceite e limites

A **6C está implementada e validada localmente**. Coleta operacional funciona mesmo com o publicador desligado; métricas HTTP permanecem protegidas pela administração bootstrap. Retenção exige `-EnableOutboxRetention` e conserva sete dias desde `published_at`, por padrão. Quarentena/liberação usa função SQL de operador autorizado e histórico transacional; não há novo endpoint de mutação.

Nenhuma credencial ou stack persistente foi alterada. A V12 foi aplicada somente nos bancos temporários; o novo JAR aplicará migrações pendentes na inicialização local. Exemplos PowerShell, links locais e `git diff --check` conferidos. Não houve correspondência entre credenciais locais e conteúdo dos arquivos alterados.

ACK continua significando aceitação pelo broker, não consumo. A retenção não implementa replay ou arquivamento de auditoria. Histórico por trigger não protege contra proprietário/superusuário que altere DDL. Métricas são locais ao processo, com consulta agregada periódica; alertas externos, Prometheus e tracing completo permanecem posteriores.

O escopo produtor/Outbox da etapa 6 está implementado localmente; publicação/CI remota e revisão continuam pendentes. Consumidores com ack manual, deduplicação por eventId, efeitos Redis e medição da propagação pertencem à **etapa 7**.

Referências: [ADR-021](../adr/021-operacao-da-outbox.md), [guia operacional](../runbooks/operacao-outbox-local.md) e [plano](../PLANO-DE-IMPLEMENTACAO.md).

# Evidências — 6B: publicação da Outbox no RabbitMQ

- Data: 2026-10-19.
- Branch: `feat/outbox-rabbitmq-publisher`, base `3159d3f` (6A2).
- Escopo: publicação confirmada, leases concorrentes, recuperação e topologia compartilhada.
- Commit, publicação e CI remota: pendentes.

## Validação

`mvnw.cmd -B -ntp clean verify` aprovado com Java 21, PostgreSQL 16.15, Vault 1.21.4 e RabbitMQ 4.3.6 temporários: **462 testes, zero falhas, zero erros e zero ignorados**. Build final concluído em 2 minutos e 12 segundos, às 12:35:48 de 2026-10-19 (UTC−03). Spotless, regras de arquitetura e cobertura JaCoCo mínima de 80% por pacote configurado aprovados, incluindo a aplicação de auditoria.

O build foi executado em cópia isolada para evitar interferência da IDE em `target`. Os 253 arquivos relevantes de código, configuração e scripts coincidem por SHA-256 com as fontes validadas. JAR preservado em `iam-core/target/iam-core-0.1.0-SNAPSHOT.jar`; log em `.firecrawl/etapa6b-verify.log`; relatórios em `.firecrawl/etapa6b-reports/`, ignorados pelo Git.

A entrega acrescenta 11 casos da aplicação, cinco do transporte, um de configuração e 12 casos de integração com PostgreSQL/RabbitMQ reais. A regressão valida também a migração V11 e a ausência do publicador quando desabilitado.

## Casos cobertos

- Publicação persistente com payload original, eventId estável, cabeçalhos e roteamento para auditoria/bloqueio; comunicação com o broker fora da transação de banco.
- Mensagem obrigatória sem rota recebe retorno mesmo com confirmação positiva e permanece pendente; nova tentativa ocorre apenas após o prazo.
- Rotas de falha de login e evolução de identidade chegam às filas previstas na topologia compartilhada.
- Lease expirado permite recuperação e impede que o proprietário anterior confirme ou reprograme a entrega.
- Falha simulada após confirmação do broker e antes da marcação no banco produz reenvio com o mesmo eventId e payload.
- Dois trabalhadores processam 20 eventos sem compartilhar uma reivindicação vigente.
- Eventos não confirmados pela transação de negócio não são publicados; reivindicação dentro de transação ambiente é recusada.
- Interrupção real do aplicativo RabbitMQ preserva a pendência; a recuperação permite publicar depois.
- Trabalhador agendado continua após falha; configuração Spring habilitada inicia a publicação e encerra seus recursos ao fechar o contexto.
- Retry com TTL retorna somente ao consumidor correspondente; rejeição definitiva encaminha à DLQ com histórico `x-death`.
- NACK, timeout, interrupção, falha de conexão/topologia, envelope inválido, backoff limitado e credenciais omitidas dos diagnósticos têm cobertura automatizada.

## Aceite e limites

A **6B está implementada e validada localmente**. O publicador permanece desabilitado por padrão e pode ser ativado por `Start-IamCore.ps1 -EnableOutboxPublisher`. A topologia empacotada é a mesma de `infra/rabbitmq/definitions.json`, sem credenciais.

A entrega oferece publicação ao menos uma vez: duplicatas após perda da confirmação no banco são esperadas e preservam eventId para a futura deduplicação dos consumidores. Não há descarte automático de eventos após repetidas falhas. Confirmação de publicação não comprova processamento pelo consumidor.

Nenhum servidor persistente ou credencial local foi alterado. A V11 foi validada nos bancos temporários; Flyway aplicará as migrações pendentes ao iniciar o novo JAR. Consumidores Redis, medição de propagação, métricas/retenção operacional e TLS de produção permanecem nas etapas previstas. Próxima entrega: **6C**, operação e observabilidade da Outbox.

Referências: [ADR-020](../adr/020-publicacao-outbox-rabbitmq.md), [guia local](../runbooks/publicacao-outbox-local.md) e [plano](../PLANO-DE-IMPLEMENTACAO.md).

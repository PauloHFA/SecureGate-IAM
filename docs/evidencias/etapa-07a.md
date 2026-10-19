# Evidências — 7A: consumidor de revogação

- Data: 2026-10-19.
- Branch: `feat/enforcer-revocation-consumer`, base `323d06e` (6C).
- Escopo: bootstrap do enforcer, ACK manual, efeitos e idempotência Redis.
- Commit, publicação e CI remota da 7A: pendentes. A 6C foi commitada em `323d06e` antes desta entrega.

## Validação

`mvnw.cmd -B -ntp clean verify` aprovado com Java 21, PostgreSQL 16.15, RabbitMQ 4.3.6, Redis 7.4.11 e Vault 1.21.4 temporários: **488 testes, zero falhas, zero erros e zero ignorados**. Build final concluído em 3 minutos e 55 segundos, às 13:27:25 de 2026-10-19 (UTC−03). Spotless, arquitetura e cobertura JaCoCo aprovados. O novo módulo também exige pelo menos 80% de cobertura de linhas por pacote de domínio/aplicação.

A entrega acrescenta quatro testes unitários, duas regras de arquitetura e dez testes de integração. O módulo isolado passou antes do build completo, que preservou a regressão dos 472 testes anteriores. Os serviços usados nos testes são descartáveis.

O build foi executado em cópia isolada para evitar interferência da IDE em `target`. Os 277 arquivos relevantes de código/configuração/scripts, incluindo Lua, coincidem por SHA-256 com as fontes validadas. JARs preservados em `iam-core/target/iam-core-0.1.0-SNAPSHOT.jar` e `policy-enforcer/target/policy-enforcer-0.1.0-SNAPSHOT.jar`; log em `.firecrawl/etapa7a-verify.log`; relatórios em `.firecrawl/etapa7a-reports/`, ignorados pelo Git.

Links locais, exemplos e script PowerShell e `git diff --check` conferidos. Não houve correspondência entre credenciais locais e conteúdo dos arquivos alterados.

## Casos cobertos

- Consumidor desabilitado por padrão; configuração Spring opt-in inicia o worker e fecha seus recursos ao encerrar o contexto.
- Mensagem persistente válida aplica a marca no Redis antes do ACK. Headers são conferidos contra o envelope.
- Falha antes do efeito não cria marcador de conclusão e mantém mensagem disponível para recuperação.
- Pausa real de três segundos no Redis provoca timeout de comando de dois segundos; a mensagem permanece para retry e depois é aplicada, inclusive se o comando inconclusivo executar após o timeout.
- Efeito já aplicado antes de um ACK simulado como perdido aceita redelivery. Dois workers podem consumir duplicatas concorrentes; a projeção conserva o mesmo vencimento.
- Duplicata válida pode reconstituir projeção ausente. Mesmo eventId com corpo diferente vai à DLQ sem aplicar o segundo alvo.
- Cabeçalhos inválidos e schema não suportado vão à DLQ sem alterar Redis. Leitor recusa excesso de tamanho, campos desconhecidos, JSON extra, produtor não suportado e mensagens sem persistência.
- Eventos fora de ordem não reduzem a expiração da família; sid encerrado conserva tombstone permanente.
- Expiração real remove a marca temporária e replay expirado não a ressuscita. Tipo Redis incompatível gera erro sem registrar conclusão.
- Reinício real do aplicativo RabbitMQ preserva a mensagem durável e o worker reconecta em nova execução.
- Testes de domínio distinguem jti, autorização e sid, conferem tolerância de expiração e representação sem identificador. Configuração exige credenciais e lotes/intervalos limitados.
- Regras de arquitetura mantêm domínio sem frameworks e aplicação sem dependências de infraestrutura/bootstrap.

O teste de redelivery grava o efeito antes de publicar novamente o mesmo corpo; não encerra à força o processo entre o retorno Redis e o ACK. O teste de broker usa `rabbitmqctl stop_app/start_app`. As verificações Redis usam serviço real autenticado e temporário.

## Aceite e limites

A entrega cobre apenas `enforcer.revocation`. Não consome bloqueio/política e não integra a consulta das marcas ao recurso protegido do IAM. Não há medição de propagação ponta a ponta nesta etapa. A ausência de prazo seguro para sid resulta em tombstone sem expiração, com custo de memória a acompanhar.

Efeitos e marcador são aplicados por script Redis; não se declara exatamente uma vez nem resistência à perda dos dados Redis. A conclusão é gravada depois do efeito. Deduplicação tem janela mínima de sete dias ou até o vencimento do efeito; replay posterior continua terminal/idempotente. Mensagens inválidas/conflitantes usam a DLQ classic local, sem demonstração de tolerância a toda falha de dead-lettering.

Nenhuma credencial ou stack persistente foi alterada. Não há migração Flyway nesta entrega. Os JARs usam somente a definição base de topologia sem credenciais. Próxima entrega: **7B**, consumidor de bloqueio/política; consulta no IAM e medição seguem nas 7C/7D.

Referências: [ADR-022](../adr/022-consumidor-de-revogacao.md), [guia local](../runbooks/enforcer-revogacao-local.md) e [plano](../PLANO-DE-IMPLEMENTACAO.md).

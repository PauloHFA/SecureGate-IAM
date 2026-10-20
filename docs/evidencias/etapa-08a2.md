# Evidências — 8A2: identidades e limites de auditoria

- Data: 2026-10-19.
- Branch: `feat/audit-database-privileges`, base `de02855` (8A1 local).
- Escopo: V14, pools/logins dedicados, provisionamento administrativo e timeouts.
- Commit, publicação e CI remota desta entrega: pendentes.

## Validação

`mvnw.cmd -B -ntp clean verify` aprovado com Java 21, PostgreSQL 16.15, RabbitMQ 4.3.6, Redis 7.4.11 e Vault 1.21.4 temporários: **558 testes, zero falhas, zero erros e zero ignorados**. Build concluído em 4 minutos e 58 segundos, às 22:34:47 de 2026-10-19 (UTC−03). Spotless, regras de arquitetura e cobertura mínima JaCoCo de 80% dos pacotes configurados aprovados nos dois serviços.

Build em cópia isolada com 312 arquivos relevantes de código/configuração/scripts idênticos por SHA-256. JARs preservados nos targets dos serviços; log `.firecrawl/etapa8a2-verify.log`; relatórios e logs locais dos processos em `.firecrawl/etapa8a2-reports/`. Artefatos são ignorados pelo Git. Links locais, exemplos e scripts PowerShell e `git diff --check` conferidos; nenhuma correspondência com credenciais locais nos arquivos alterados. Regressão inclui os oito casos ponta a ponta anteriores.

## Casos exercitados

Sete testes de integração PostgreSQL e dois testes unitários novos verificam:

- A composição Spring mantém o DataSource/JdbcTemplate principal com o proprietário e usa os dois logins dedicados nos pools de auditoria. Flyway continua principal e a V14 é aplicada uma vez.
- Ingestão real pelo store confirma APPENDED/DUPLICATE e um recibo por eventId, sem SELECT/INSERT direto nos recibos.
- Ingestão não pode UPDATE/DELETE/TRUNCATE o log, escrever recibos, ler auditoria legada/Outbox, criar objetos, desabilitar triggers, executar manutenção ou SET ROLE para proprietário/manutenção. SET ROLE para o próprio grupo não aumenta privilégios.
- Manutenção só usa o wrapper limitado. Horizonte 13, função histórica, leitura/escrita de eventos, DDL e truncamento são recusados. Falhas de permissão verificam SQLSTATE 42501.
- Novas partições são acessíveis por INSERT/SELECT no parent sem grants extras nos filhos; leitura direta, truncamento e DROP dos filhos continuam recusados.
- Lock advisory mantido por outra conexão aciona lock timeout em menos de cinco segundos; liberar o lock permite nova chamada e tick sem reinício.
- Login autenticado recebe defaults de statement/lock/idle de 5/1/10 segundos. pg_sleep(10) é cancelado com SQLSTATE 57014 e a sessão continua utilizável.
- Reprovisionamento usa o prefixo psql real do script PowerShell com variáveis de ambiente no contêiner descartável; não imprime nenhuma das duas senhas. Dados e credenciais permanecem utilizáveis.
- Configuração não pode usar username proprietário ou senha embutida na URL. Arquivo ausente/inválido não permite fallback; exceções não mostram o conteúdo da credencial.

Os testes existentes de configuração foram adaptados para o modo dedicado e a manutenção agora usa o relógio UTC do banco. Upgrade/migrações foram atualizados para V14. Os testes da 8A1 continuam cobrindo concorrência, atomicidade e imutabilidade do schema.

## Operação e limites

O script administrativo é entregue pronto, mas não foi executado contra a stack persistente. As credenciais dos testes são geradas e removidas ao finalizar; nenhuma credencial local ou `.env` foi alterado. Após aplicar V14 em um startup sem flags, o operador pode provisionar e ativar os modos conforme o guia.

O store restrito é opcional; não há consumidor RabbitMQ nesta entrega. A próxima **8B** integra lote, confirmação após commit, conflito/retry e recuperação. Consulta, retenção e carga continuam posteriores.

A conexão principal do processo IAM continua proprietária e usada por Flyway/contextos anteriores. Esta entrega protege os caminhos dedicados; não declara isolamento completo do processo contra comprometimento dessa credencial, nem proteção contra administrador que altere grants/DDL. Separação por banco foi testada com uma instância local, sem promessa de hardening de cluster ou TLS.

Limites de pool/driver/statement são por operação, não prazo total de lote. O cliente pode alterar GUCs permitidos em suas próprias sessões; os defaults não são um limite inviolável contra cliente comprometido. Senhas em arquivo são carregadas no startup, sem rotação automática; custódia Vault/credenciais temporárias ficam na etapa 9.

Referências: [ADR-029](../adr/029-identidades-auditoria.md), [guia](../runbooks/identidades-auditoria-local.md) e [plano](../PLANO-DE-IMPLEMENTACAO.md).
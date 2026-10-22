# Evidências — Etapa 9A2b

- Data/responsável: 20/10/2026, PauloHFA com assistência Codex; horários UTC−03.
- Branch: `feat/vault-dynamic-database`, baseada na 9A2a `7a185e6`.
- Commit/publicação/CI remota: pendentes nesta validação local.
- Ambiente: Windows, Java 21.0.8, Maven Wrapper 3.9.11, Docker Engine 29.9.1; Vault 1.21.4 e PostgreSQL 16.15 em Testcontainers.

## Entrega

Database Engine opcional com AppRole runtime separado, KV sem credencial proprietária e pool principal com login PostgreSQL temporário. Processo administrativo `--migrate-only` aplica V1–V17 e o perfil de execução sem iniciar a aplicação web. Emissor PostgreSQL dedicado, SQL de emissão/revogação e rotação administrativa de sua senha no Vault. Script runtime desativa Flyway e omite variáveis administrativas/credenciais estáticas.

Pool fecha dois segundos antes do menor prazo entre token pai e lease, incluindo sessões emprestadas; revoga o token pai ao encerrar e recusa conexões posteriores. Sem renovação, fallback ou recuperação automática de pool nesta entrega. Métricas não incluem IDs ou segredos.

## Validação focada

```powershell
.\mvnw.cmd -B -ntp spotless:apply
.\mvnw.cmd -B -ntp -pl iam-core -am '-Dtest=VaultKvEnvironmentPostProcessorTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=VaultDatabaseIT' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djacoco.skip=true' verify
```

Primeira validação: 17 testes KV anteriores e sete integrações novas aprovados, zero falhas/erros/ignorados. Conclusão às 01:12:14, duração Maven 39,688 s. Após essa seleção, foram adicionadas validações de URL e testes unitários de falha; a revisão final é coberta pelo build completo abaixo.

Testes reais: emissor não superusuário com ADMIN no grupo sem INHERIT/SET, senha inicial invalidada pela rotação Vault, política runtime sem acesso ao KV proprietário/configuração/outro papel, startup do IAM com credenciais locais inválidas, criação HTTP de usuário/auditoria/Outbox com login `sg_*`, negação de DDL, revogação ao fechar e expiração com conexão emprestada. O JAR é executado duas vezes como migrador separado, sem Tomcat e sem senha no log.

## Build completo

```powershell
.\mvnw.cmd -B -ntp clean verify
```

**Build completo aprovado: 668 testes em 63 relatórios XML, zero falhas, zero erros e zero ignorados**, incluindo os oito cenários ponta a ponta anteriores. Conclusão às 01:19:57, duração Maven de 6 min e 49 s. Spotless, ArchUnit e cobertura mínima JaCoCo de 80% dos pacotes configurados aprovados nos dois serviços.

Log ignorado: `.firecrawl/etapa9a2b-verify.log`. Snapshot de relatórios de testes/cobertura preservado em `.firecrawl/etapa9a2b-full-reports/`. O benchmark optativo da 8D2 não integra esse build.

Na revisão final, o agendador foi configurado para cancelar tarefas pendentes ao fechar o pool, evitando reter a thread até o TTL. Acrescentada verificação de término da thread no teste de fechamento. O Java final passou em seleção focada de **42 casos** (18 de falhas, 17 KV anteriores e sete integrações reais), zero falhas/erros/ignorados; conclusão às 01:20:51, duração Maven de 35,594 s. Comando equivalente ao focado acima, usando `-Dtest=VaultDatabaseFailuresTest,VaultKvEnvironmentPostProcessorTest`. Log `.firecrawl/etapa9a2b-final-focused.log`; JaCoCo não foi recalculado nessa seleção. JAR e relatórios focados atualizados nos targets. O build completo antecede somente esse ajuste de encerramento do agendador e a última limpeza de variáveis no script, validada pela fixture abaixo.

## Verificações auxiliares

Os três scripts PowerShell novos/alterados passaram no parser. `sh -n` aprovou `infra/vault/init.sh` usando a imagem local do Vault, sem executar bootstrap ou usar a stack persistente. Fixture sintética com Java/Docker/HTTP simulados validou seleção do AppRole runtime, omissão de credenciais administrativas/estáticas no runtime, envio administrativo por ambiente/JSON, SQL via stdin e chamada de rotação do emissor. Fixture ignorada em `.firecrawl/validate-9a2b-scripts.ps1`.

653 links Markdown locais válidos, zero quebrados; nenhuma correspondência com credenciais locais nas alterações e `git diff --check` sem erros.

## Limites

A stack persistente não foi alterada para os testes. Scripts administrativos são destinados à preparação explícita do ambiente local. Renovação, troca segura do pool e recuperação contínua ficam na 9A2c. O processo interrompe operações no vencimento; não demonstra disponibilidade contínua. Vault dev/SQL local sem TLS não são configuração de produção. PAM, KV no enforcer e auditoria operacional integrada permanecem pendentes.

Referências: [ADR-036](../adr/036-credenciais-dinamicas-postgresql.md), [guia](../runbooks/vault-database-local.md), [plano](../PLANO-DE-IMPLEMENTACAO.md).

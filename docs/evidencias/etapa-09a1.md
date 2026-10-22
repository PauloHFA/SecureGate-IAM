# Evidências — Etapa 9A1

- Data/responsável: 20/10/2026, PauloHFA com assistência Codex; horários UTC−03.
- Branch: `feat/vault-kv-bootstrap`, baseada na 8D2 `d6b2af0`.
- Commit/publicação/CI remota: pendentes nesta validação local.
- Ambiente: Windows, Java 21.0.8, Maven Wrapper 3.9.11, Docker Desktop/Engine 29.9.1; Vault 1.21.4 e PostgreSQL 16.15 em Testcontainers.

## Entrega

Bootstrap opcional KV v2 antes de criar DataSource/Flyway e clientes do IAM. AppRole por arquivos, leitura somente do próprio documento, seleção de cinco campos conhecidos, publicação atômica com precedência sobre configuração local e revogação do token antes da publicação. Erros genéricos sem causas ou resposta externa. `Start-IamCore.ps1 -EnableVaultKv` não envia as credenciais equivalentes de banco/Redis/broker no ambiente do processo Java.

## Testes focados

```powershell
.\mvnw.cmd -B -ntp spotless:apply
.\mvnw.cmd -B -ntp -pl iam-core -am '-Dtest=VaultKvEnvironmentPostProcessorTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=VaultKvBootstrapIT' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djacoco.skip=true' verify
```

**19 casos aprovados:** 17 unitários e dois de integração, zero falhas/erros/ignorados. Conclusão às 00:27:24, duração Maven 20,595 s. JaCoCo foi desativado somente nessa seleção focada; a verificação de cobertura pertence ao build completo abaixo.

Unitários: precedência, lista permitida, redação de representação textual, revogação, modo desativado sem acesso a arquivos/Vault, erro de login, 403/404, documento incompleto, versão inválida, segredo excluído/destruído, JSON inválido, falha de revogação, endereço inseguro e arquivo AppRole ausente. Em falhas não se publica PropertySource parcial nem causa externa.

Integração real: descoberta do processor por `spring.factories`, inicialização do IAM/Flyway/PostgreSQL com senha obtida exclusivamente do KV, apesar de credenciais locais deliberadamente inválidas. Verificação de `current_user` e migrações V1–V16. Política real `infra/vault/iam-core.hcl` usada pelo AppRole. Segundo caso usa AppRole sem acesso ao documento e comprova negação sem snapshot/fallback. Dados e credenciais de teste são sintéticos e os serviços descartáveis.

## Build completo

```powershell
.\mvnw.cmd -B -ntp clean verify
```

**Build aprovado: 617 testes, zero falhas, zero erros e zero ignorados**, somados a partir de 60 relatórios XML. Inclui os oito testes ponta a ponta anteriores. Conclusão às 00:34:41, duração Maven 5 min e 51 s. Spotless, ArchUnit e cobertura mínima JaCoCo de 80% dos pacotes configurados aprovados nos dois serviços. Executado diretamente no workspace, sem usar a stack persistente.

Log local ignorado: `.firecrawl/etapa9a1-verify.log`. Relatórios padrão: `target/surefire-reports`, `target/failsafe-reports` e `target/site/jacoco` dos módulos. JARs atualizados nos targets locais. O benchmark optativo da 8D2 não integra o build padrão.

## Verificações auxiliares

Script PowerShell sem erros de parsing. Execução com Java simulado, em fixture sintética isolada: a flag KV chega ao processo, as credenciais equivalentes de PostgreSQL/Redis/RabbitMQ são omitidas e o ambiente é restaurado ao encerrar. Teste negativo do JAR empacotado confirma descoberta do processor e interrupção do startup com AppRole ausente. Logs/fixture em `.firecrawl/`, ignorados pelo Git.

627 links Markdown locais válidos, zero quebrados. Nenhuma correspondência com credenciais locais nos arquivos alterados/novos. `git diff --check` sem erros.

## Limites

Entrega de KV apenas no IAM, snapshot estático de startup. Conta principal ainda proprietária/migradora; pools dedicados de auditoria continuam por arquivos. Sem credenciais dinâmicas, renovação, troca de pool, integração KV no enforcer ou endpoint PAM. Esses itens permanecem abertos na etapa 9. Nenhuma mudança na stack persistente, política Vault ou segredo local foi necessária para os testes. A perda de chaves no Vault dev não é recuperável por este bootstrap.

Referências: [ADR-034](../adr/034-bootstrap-kv-vault.md), [guia](../runbooks/vault-kv-local.md) e [plano](../PLANO-DE-IMPLEMENTACAO.md).

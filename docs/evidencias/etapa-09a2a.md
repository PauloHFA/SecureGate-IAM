# Evidências — Etapa 9A2a

- Data/responsável: 20/10/2026, PauloHFA com assistência Codex; horários UTC−03.
- Branch: `feat/runtime-database-privileges`, baseada na 9A1 `c3d8d6b`.
- Commit/publicação/CI remota: pendentes nesta validação local.
- Ambiente: Windows, Java 21.0.8, Maven Wrapper 3.9.11, Docker Engine 29.9.1; PostgreSQL 16.15 descartável em Testcontainers.

## Entrega

Grupo NOLOGIN `securegate_iam_runtime` com concessões explícitas de negócio e auditoria produtora, sem DDL nos contextos ou acesso ao Flyway/log particionado. UPDATE da Outbox limitado aos campos de entrega. Provisionamento administrativo separado, transacional e repetível, sem criar credenciais. V17 fixa `search_path`, qualifica o alvo e usa SECURITY DEFINER no trigger para evitar INSERT direto no histórico operacional pelo runtime.

A expectativa do teste de upgrade a partir da V5 foi atualizada para doze migrações adicionais, incluindo a V17, mantendo as verificações de preservação da política e auditoria legadas. Os testes de bootstrap e upgrade a partir da V12 também passam a verificar a versão final 17, preservando as checagens de idempotência e dos dados anteriores.

## Teste focado

```powershell
.\mvnw.cmd -B -ntp spotless:apply
.\mvnw.cmd -B -ntp -pl iam-core -am '-Dtest=NoFocusedUnitTests' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=RuntimeDatabasePrivilegesIT' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djacoco.skip=true' verify
```

**26 casos aprovados**, zero falhas/erros/ignorados. Conclusão às 00:49:02; duração Maven de 24 segundos. Cobertura não é avaliada nessa seleção focada; será verificada no build completo.

O teste executa as migrações como proprietário e inicia o IAM com um login sintético restrito e Flyway desativado. Comprova provisionamento/consulta/bloqueio de usuário, criação de papel, auditoria e Outbox juntas, rollback, claim/quarentena/histórico e retenção preservando auditoria. O histórico mantém `database_actor` como login original apesar da função SECURITY DEFINER.

Vinte cenários negativos verificam SQLSTATE 42501 para criação/alteração/drop/truncate em objetos da aplicação, criação de schema/papel, SET ROLE para proprietário, acesso Flyway/log/recibos/catálogo, funções de manutenção/arquivo/quarentena, alteração de payload/auditoria e INSERT/TRUNCATE no histórico operacional. Casos adicionais verificam reaplicação, remoção de ACL indevida de coluna, rejeição de grupo elevado e ausência de concessões automáticas em tabela nova.

## Build completo

```powershell
.\mvnw.cmd -B -ntp clean verify
```

**Build aprovado: 643 testes, zero falhas, zero erros e zero ignorados**, somados a partir de 61 relatórios XML, incluindo os oito cenários ponta a ponta anteriores. Conclusão às 01:03:03; duração Maven de 5 min e 42 s. Spotless, ArchUnit e cobertura mínima JaCoCo de 80% dos pacotes configurados aprovados nos dois serviços.

Log local ignorado: `.firecrawl/etapa9a2a-verify.log`; relatórios de testes/cobertura e JARs atualizados nos targets dos módulos. O benchmark optativo da 8D2 não integra o build padrão.

## Verificações auxiliares

Script PowerShell sem erros de parsing. Em fixture sintética com Docker simulado, SQL versionado enviado por stdin e senha administrativa passada apenas pelo ambiente; falha do comando propagada e `PGPASSWORD` restaurado tanto no sucesso quanto na falha. Fixture em `.firecrawl/validate-runtime-provisioner.ps1`, ignorada pelo Git; não houve operação sobre a stack persistente.

638 links Markdown locais válidos, zero quebrados; nenhuma correspondência com credenciais locais nos arquivos alterados/novos e `git diff --check` sem erros.

## Limites

A stack persistente e suas credenciais não foram alteradas para validar a entrega. O script operacional será usado pelo administrador ao preparar essa stack. O grupo ainda não é usado pelo startup padrão. Database Engine, emissão temporária e separação automatizada migração/runtime ficam na 9A2b; leases, renovação e rotação dos pools, na 9A2c. Não se declara o aceite da etapa 9 concluído nem bloqueio de direitos usuais de PUBLIC, TEMP ou superusuário.

Referências: [ADR-035](../adr/035-permissoes-postgresql-runtime.md), [guia](../runbooks/permissoes-runtime-local.md) e [plano](../PLANO-DE-IMPLEMENTACAO.md).

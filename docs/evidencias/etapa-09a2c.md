# Evidências — Etapa 9A2c

- Data/responsável: 20/10/2026, PauloHFA com assistência Codex; horários UTC−03.
- Branch: `feat/vault-database-lease-lifecycle`, baseada na 9A2b `9be0356`.
- Commit local: inclui esta entrega e suas evidências; publicação e CI remota pendentes.
- Ambiente: Windows, Java 21.0.8, Maven Wrapper 3.9.11, Docker Engine 29.9.1; Vault 1.21.4/PostgreSQL 16.15 descartáveis.

## Entrega

DataSource estável, gerações com tokens/leases independentes, renovação na metade do TTL confirmado, validação de candidato antes da troca e drenagem limitada a cinco segundos. Watchdog sem chamadas ao Vault e verificação no empréstimo impedem servir após o prazo utilizável. Limite de três gerações no total (até duas em retirada com uma ativa), revogação com repetição e recuperação automática por nova autenticação. SQL de revogação idempotente, sem nova migração Flyway. ACL por prefixo de lease e parâmetros permitidos; métricas sem dados sensíveis.

## Validação focada

```powershell
.\mvnw.cmd -B -ntp spotless:apply
.\mvnw.cmd -B -ntp -pl iam-core -am '-Dtest=VaultDatabaseFailuresTest,VaultKvEnvironmentPostProcessorTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=VaultDatabaseIT' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djacoco.skip=true' verify
```

Primeira seleção aprovada: 35 casos unitários anteriores e dez integrações reais, zero falhas/erros/ignorados. Conclusão às 01:38:37, duração Maven de 1 min e 20 s. Posteriormente foram acrescentadas a verificação de conexão não devolvida, a falha/repetição de revogação durante pausa, login removido e os testes unitários do protocolo de renovação. A revisão final está no build completo abaixo.

## Build completo

```powershell
.\mvnw.cmd -B -ntp clean verify
```

Aprovado às 01:48:06, duração Maven de 7 min e 21 s: **681 testes**, 64 relatórios XML, zero falhas, erros ou ignorados. Inclui onze integrações reais de `VaultDatabaseIT`, nove casos unitários novos de `VaultDatabaseSessionLifecycleTest` e oito cenários de `PropagationIT` com os JARs reais. Os gates JaCoCo dos dois serviços passaram. Log ignorado: `.firecrawl/etapa9a2c-verify.log`; relatórios/JARs nos targets dos módulos.

Na revisão posterior, o limite de gerações foi corrigido para também contar a ativa vencida enquanto sua revogação estiver pendente. Um novo cenário real nega `revoke-self`, acumula três gerações, exige recusa de conexões sem criar a quarta e restaura a permissão para comprovar recuperação. O ajuste passou na seleção abaixo:

```powershell
.\mvnw.cmd -B -ntp spotless:apply
.\mvnw.cmd -B -ntp -pl iam-core -am '-Dtest=VaultDatabaseSessionLifecycleTest,VaultDatabaseFailuresTest,VaultKvEnvironmentPostProcessorTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=VaultDatabaseIT' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djacoco.skip=true' verify
```

Aprovada às 01:50:23, duração Maven de 2 min e 1 s: **56 testes** (44 unitários e doze integrações reais), zero falhas, erros ou ignorados. Log ignorado: `.firecrawl/etapa9a2c-final-focused.log`. O inventário dos 64 relatórios contém **682 casos distintos aprovados**, combinando o build completo e esta seleção. Não foi repetido o build completo após esse ajuste; os gates de cobertura e testes dos demais módulos correspondem à execução anterior.

## Verificações auxiliares

- `git diff --check` aprovado.
- 668 links locais Markdown verificados, nenhum quebrado.
- JSON do papel Database Engine válido; HCL e SQL exercitados pelos containers reais.
- Sete valores de credenciais locais comparados em memória com os arquivos alterados: nenhuma correspondência; valores não exibidos.

## Limites

Stack persistente e segredos locais não foram modificados para os testes. A validação usa TTLs curtos e serviços ociosos; não demonstra SLA sob carga. Não há fallback nem preservação garantida de transações além da drenagem/prazo. Falhas persistentes de revogação podem adiar trocas; limpeza após abandono local exige observação do Vault pelo operador. Transit/KV têm ciclos independentes, e KV do enforcer, PAM e auditoria operacional ainda não estão concluídos.

Referências: [ADR-037](../adr/037-renovacao-e-troca-de-pools.md), [guia](../runbooks/vault-database-renovacao-local.md) e [plano](../PLANO-DE-IMPLEMENTACAO.md).

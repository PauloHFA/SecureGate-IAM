# Evidências — 10B3b2 clientes HTTP Vault

- Data: 21/10/2026, UTC−03.
- Base: d0ef5e2; branch feat/vault-http-client-tracing.
- Estado: implementação e validação locais concluídas; incluída neste commit; push/CI remota pendentes.

## Alterações

VaultHttpTransport compartilhado e injeção em Transit/MFA, sessões de banco dinâmico e PAM. Construtores anteriores sem tracing preservados. Limites de origem/path/redirect e schema de quatro atributos; sem body/headers/segredos/Throwable em spans. Sem nova dependência, exporter/backend, alteração da stack persistente/.env ou schema V22. KV pré-Spring e contexto assíncrono fora do escopo. [ADR-059](../adr/059-tracing-clientes-http-vault.md), [operação](../runbooks/tracing-http-local.md).

## Seleção direcionada

```powershell
.\mvnw.cmd -B -ntp -pl iam-core -am spotless:apply '-Dtest=VaultHttpTransportTest,VaultDatabaseSessionLifecycleTest,VaultDatabaseFailuresTest,VaultTransitSigningKeysTest,IamArchitectureTest,ArchitectureRulesTest,HttpTracingConfigurationTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=HttpServerTracingIT,PrometheusIT' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djacoco.skip=true' verify
```

BUILD SUCCESS às **15:36:13 UTC−03**: **74 testes unitários/arquitetura + 7 integração**, zero falhas/erros/skips. Unitários do transporte (5) verificam corpo/token preservados, contexto ausente por padrão, filho/restauração, destinos/rotas/query inelegíveis, clientes com redirects habilitados sem contexto, 403/500/302 e IOException/interrupção sem texto nos spans. HttpServerTracingIT (4) acrescenta requisição IAM real que cria sessão AppRole, consulta e revoga por JDK, com três spans CLIENT filhos do servidor e sem baggage/tracestate/segredos. Vault HTTP é simulado; PostgreSQL e IAM/segurança/servlet são reais. PrometheusIT (3) preserva padrão e métricas.

Na revisão, o bean de transporte passou a receber dependências opcionais por parâmetros em vez de resolver beans a cada envio, para preservar lifecycle no shutdown. Essa revisão está incluída no build completo abaixo. Não houve edição manual de fonte após o início desse build; Spotless aplicou formatação antes da compilação.

## Build completo

Comando: `.\mvnw.cmd -B -ntp spotless:apply verify`.

**BUILD SUCCESS às 15:46:50 UTC−03**, **10 min 27 s**, exit code nativo **0**. **810 testes**, zero falhas/erros/skips: security-events 43; IAM 399 unitários + 291 integração; enforcer 44 unitários + 22 integração; fim a fim 11. Dois gates JaCoCo atendidos; arquitetura incluída. A seleção anterior pula JaCoCo e se sobrepõe à suíte completa; não somar contagens. Smokes opt-in de observabilidade pelo nome não foram executados.

**Nenhum timeout de encerramento do fork foi registrado nesta execução.** Isso não demonstra correção do aviso histórico da 10B3b1, que continua como pendência de lifecycle. Não alterou timeouts de testes, hooks ou Hikari para obter este resultado.

Verificados **1.268 links locais** em README/docs sem destinos ausentes; **20 arquivos alterados/novos** UTF-8 sem BOM e sem whitespace final; git diff --check aprovado. Logs ignorados locais: .firecrawl/etapa10b3b2-targeted.log e .firecrawl/etapa10b3b2-verify.log.

## Limites

Sem tracing do KV anterior ao Spring, spans internos do Vault, metadata de todas as operações sob Vault real, continuidade entre threads, exporter/backend ou teste de capacidade. Rotas inelegíveis continuam sendo enviadas pelo transporte existente sem novo contexto; controle de propagação não autoriza destino/credencial. Parse/validação de respostas está fora do span de transporte. Pendentes 10B3c outbox/AMQP, 10C/10D e publicação/CI. Ressalva histórica de encerramento Hikari da [10B3b1](etapa-10b3b1.md) não foi tratada por esta mudança.

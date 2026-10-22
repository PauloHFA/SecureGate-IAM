# Evidências — 10B2b2c: coleta Redis

- Data: 21/10/2026, America/Sao_Paulo.
- Branch: feat/redis-prometheus-metrics, base 6a48bb6.
- Estado: implementação e validação locais concluídas; incluída neste commit; push/CI remota pendentes.
- Escopo: ACL própria persistida em arquivo, exporter opcional por digest, job/migração e dashboard de seis painéis.

## Validação

Primeira compilação do smoke identificou uso incompatível da API Lettuce (ACL SETUSER recebe AclSetuserArgs) e Runnable em assertThrows. Corrigidos para o builder da versão disponível e Executable JUnit; a primeira tentativa não executou testes. A restauração do grant injetado deixou uma regra -get redundante e foi recusada pelo preparador estrito; a fixture passou a recarregar seu ACL file original. A primeira tentativa de conexão do exporter também revelou que o mapa de senha precisa incluir o usuário na URI do lookup; corrigido para redis://securegate_metrics@redis:6379 conforme o código da versão fixada. Estes resultados intermediários não foram aceitos como validação concluída.

Validação funcional após as correções:

```powershell
.\mvnw.cmd -B -ntp -pl integration-tests -am spotless:apply '-Dtest=NoSelectedUnitTests' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=RedisObservabilitySmoke' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djacoco.skip=true' verify
```

**BUILD SUCCESS às 13:44:47 UTC−03**, duração **1 min e 18 s**, **1 teste** (69,10 s), zero falhas/erros/ignorados. Log ignorado .firecrawl/etapa10b2b2c-smoke.log. Spotless/compilação/enforcer de ambiente aprovados; suíte padrão e JaCoCo não executados nessa seleção.

Seleção incluindo regressão IAM e JSON inválido sem sobrescrita:

```powershell
.\mvnw.cmd -B -ntp -pl integration-tests -am spotless:apply '-Dtest=NoSelectedUnitTests' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=RedisObservabilitySmoke,ObservabilitySmoke' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djacoco.skip=true' verify
```

**BUILD SUCCESS às 13:47:52 UTC−03**, duração **2 min e 21 s**, **2 testes**, zero falhas/erros/ignorados; exit code nativo 0. Redis: 64,66 s; regressão IAM: 66,76 s. Log ignorado .firecrawl/etapa10b2b2c-final.log.

Revisão final identificou que usuários Redis e URI do mapa precisam de comparação sensível a maiúsculas. Preparador alterado para recusar DEFAULT como usuário extra e URI com SECUREGATE_METRICS, em vez de tratá-los como aliases das identidades minúsculas. Adicionados casos negativos ao smoke; reexecutado somente Redis:

```powershell
.\mvnw.cmd -B -ntp -pl integration-tests -am spotless:apply '-Dtest=NoSelectedUnitTests' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=RedisObservabilitySmoke' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djacoco.skip=true' verify
```

**BUILD SUCCESS às 13:49:35 UTC−03**, duração **1 min e 21 s**, **1 teste** (72,12 s), zero falhas/erros/ignorados; exit code nativo 0. Log ignorado .firecrawl/etapa10b2b2c-case-sensitive.log. DEFAULT e URI com usuário em maiúsculas foram recusados; fluxo válido, ACL/AOF/Grafana e recuperação passaram. As seleções se sobrepõem e não devem ser somadas como testes distintos.

Checks finais: dois scripts com parser PowerShell aprovado, dashboard JSON com seis painéis/seis expressões, 1.263 links locais válidos e 56 arquivos de evidências referenciados no guia. Os 21 arquivos alterados foram comparados em memória com cinco valores privados de .env, sem ocorrências; sem whitespace final e git diff --check aprovado. Projetos descartáveis de Redis/coleta encerrados com volumes removidos. Suíte padrão, arquitetura/cobertura completas e benchmark não foram executados nesta entrega; não há Java de produção alterado.

| Contrato | Verificação |
| --- | --- |
| Infra real | Redis 7.4.11 e exporter 1.95.0-alpine/digest, Compose/scripts versionados em cópia isolada, Prometheus/Grafana reais. |
| Preparação | Usuário extra (inclusive DEFAULT com caixa diferente) e grant GET/padrão de chave inesperados causam recusa; arquivos existentes válidos preservados e ACL divergente não sobrescrita. |
| Arquivos inválidos | JSON vazio/URI com caixa incorreta e ACL divergente recusados sem sobrescrita; restauração explícita apenas da fixture de teste. |
| Credencial | Senha CSPRNG própria no mapa com URI que inclui username; nenhum valor de senha no ambiente/URI/argumentos do exporter. |
| ACL efetiva | GET/SET/SCAN/CONFIG/ACL/EVAL/FLUSHDB/SLOWLOG GET com metrics recebem NOPERM; INFO permitido. |
| Coleta | up=1, redis_up=1, last_scrape_error=0 e chaves agregadas >=1, sem nome/valor do canário ou senha no scrape. |
| Superfície HTTP | /scrape para alvo arbitrário retorna 404. |
| Migração | Colisão recusada sem escrita, backup igual ao original, duplicidade recusada; compose.env/senha Grafana preservados. |
| Grafana | Seis expressões retornam dados reais, datasource gera frame e acesso anônimo retorna 401. |
| Falha/recuperação | Senha do usuário metrics alterada no Redis de teste produz redis_up=0 com up=1; restaurar senha recupera redis_up=1. |
| Persistência | Após reinício Redis, métricas autenticadas e acesso default funcionam; valor do canário permanece no volume AOF. |
| Artefatos | Scrape/logs/configuração fora do diretório privado comparados com credenciais e canário; exporter.log sem ocorrências. |
| Limpeza | Somente os dois projetos gerados e seus volumes removidos; credenciais/ACL de teste apagados em finally. |

Somente projetos descartáveis são usados. O preparador não foi executado na stack persistente e .env do usuário não foi alterado.

## Limites e próximo passo

SLOWLOG GET é tentado pelo exporter e recusado intencionalmente; não há métricas do último comando lento. INFO e estatísticas agregadas são o contrato desta coleta; sem acesso a chaves/canais ou ACL administrativa. Overlay exige reinício Redis para carregar arquivo, mantendo senha/permissões default e AOF. Não é integração genérica com ACL customizada.

Sem Java de produção, migrations ou alterações de dados/grants IAM. Host/rede local sem TLS/autenticação HTTP no exporter. Smoke funcional não mede capacidade/SLA; seleção opt-in não comprova suíte padrão/cobertura completa. Próxima: 10B2b2d — Vault, seguida por 10B3 logs/tracing e 10C–10D.

[ADR-055](../adr/055-coleta-opcional-redis.md), [operação](../runbooks/metricas-redis-local.md), [passo a passo](../GUIA-DE-IMPLEMENTACAO.md), [inventário](../OBSERVABILIDADE.md).

# Evidências — 10B2b2b: coleta PostgreSQL

- Data: 21/10/2026, America/Sao_Paulo.
- Branch: feat/postgres-prometheus-metrics, base eea6f52.
- Estado: implementação e validação locais concluídas; incluída neste commit; push/CI remota pendentes.
- Escopo: login próprio, exporter opcional fixado por digest, job/migração com backup e dashboard de seis painéis.

## Validação

Primeira seleção:

```powershell
.\mvnw.cmd -B -ntp -pl integration-tests -am spotless:apply '-Dtest=NoSelectedUnitTests' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=PostgresObservabilitySmoke' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djacoco.skip=true' verify
```

**BUILD SUCCESS às 13:22:58 UTC−03**, duração **1 min e 24 s**, **1 teste** (73,83 s), zero falhas/erros/ignorados; exit code nativo 0. Log ignorado .firecrawl/etapa10b2b2b-smoke.log. Spotless/compilação/enforcer de ambiente aprovados. Suíte padrão e JaCoCo não executados nesta seleção.

Seleção final, após tornar o formato do arquivo estrito (recusar newline), acrescentar recusa de NOINHERIT e exigir dados reais do background writer:

```powershell
.\mvnw.cmd -B -ntp -pl integration-tests -am spotless:apply '-Dtest=NoSelectedUnitTests' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=PostgresObservabilitySmoke,ObservabilitySmoke' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djacoco.skip=true' verify
```

**BUILD SUCCESS às 13:27:35 UTC−03**, duração **2 min e 43 s**, **2 testes**, zero falhas/erros/ignorados; exit code nativo 0. PostgreSQL: 84,64 s; regressão IAM: 68,36 s. Log ignorado .firecrawl/etapa10b2b2b-final.log. As seleções se sobrepõem e não devem ser somadas como testes distintos.

Checks adicionais: dois scripts com parser PowerShell aprovado; dashboard JSON com seis painéis/seis expressões; 1.232 links locais válidos e 55 arquivos de evidências referenciados no guia; 21 arquivos alterados comparados em memória com cinco valores privados de .env, zero ocorrências; sem whitespace final e git diff --check aprovado. Nenhum projeto Docker de smoke PostgreSQL/coleta permaneceu após a limpeza. Suíte padrão/arquitetura e cobertura completas não foram repetidas; não há Java de produção alterado.

| Contrato | Verificação |
| --- | --- |
| Infra real | Compose base/overlay/SQL/scripts versionados copiados para workspace isolado; PostgreSQL 16.15, exporter 0.19.1/digest, Prometheus e Grafana reais. |
| Segregação da credencial | Arquivo 64 hex distinto de .env; exporter recebe apenas caminho, nunca senha no ambiente/URI. |
| Papel próprio | pg_monitor, sem superuser/createdb/createrole/replication/bypassrls, limite de três conexões, default read-only. |
| ACL real | Com read-only desligado, SELECT/INSERT de tabela protegida, CREATE em public e SET ROLE administrativo recusados com SQLSTATE 42501. |
| Estado inesperado | Grant SELECT preexistente causa recusa do provisionador; arquivo permanece igual, ACL removida explicitamente e reexecução aprovada. |
| Arquivo inválido | Newline após a senha faz o provisionador recusar o arquivo sem sobrescrever nem alterar o login; conteúdo válido restaurado explicitamente pelo teste. |
| Migração da coleta | Colisão recusada sem escrita, backup igual ao original, duplicidade recusada, compose.env/senha Grafana preservados. |
| Estatísticas | Transações SQL reais e incremento de commits no Prometheus; consultas incluem monitoração, sem medir throughput de negócio; background writer também retornou séries reais. |
| Dados sensíveis | Scrape sem canário de tabela, nome da tabela, senha ou label query; artefatos não privados comparados com credenciais geradas. |
| Grafana | Seis expressões com dados reais, frame via datasource e acesso anônimo 401. |
| Falha SQL | Credencial alterada no banco e sessões só do login de teste encerradas; exigir pg_up=0 e up=1, depois restaurar credencial e pg_up=1. |
| Limpeza | Remover somente os dois projetos gerados com volumes e os arquivos de credenciais de teste, em finally. |

PostgreSQL/exporter e coletores iniciados somente em projetos descartáveis. Não houve execução do provisionador contra a stack persistente nem modificação de .env do usuário. Revisões anteriores são evidências históricas, não novos builds completos desta entrega.

## Limites e próximo passo

Não altera Java de produção, migrations V22 ou grants IAM/auditoria/PAM. SQL administrativo fora do Flyway cria somente o login de monitoração; pg_monitor permite leitura ampla de estatísticas do cluster e o padrão read-only não substitui ACL. Endpoint HTTP e SQL local sem TLS; proteger host/rede e arquivos. Smoke funcional não mede capacidade nem cobre autenticação/negócio IAM; seleção opt-in não comprova suíte padrão/cobertura completa.

Próxima: 10B2b2c — Redis; depois 10B2b2d — Vault, 10B3 logs/tracing e 10C–10D medição/ajustes.

[ADR-054](../adr/054-coleta-opcional-postgresql.md), [operação](../runbooks/metricas-postgres-local.md), [passo a passo](../GUIA-DE-IMPLEMENTACAO.md), [inventário](../OBSERVABILIDADE.md).

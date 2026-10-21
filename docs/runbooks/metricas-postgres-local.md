# Métricas locais PostgreSQL — 10B2b2b

## Preparação e permissões

Requisitos: PostgreSQL local saudável, .env gerado conforme [infra](../../infra/README.md), Docker Desktop/Windows, PowerShell e coleta preparada conforme [10B2a](coleta-iam-local.md). Comandos na raiz. Não é necessário reiniciar o banco nem inicializar Vault/RabbitMQ para provisionar a monitoração. A entrega foi validada em banco descartável; a infraestrutura persistente do usuário depende da execução explícita dos comandos abaixo.

```powershell
.\infra\scripts\Provision-PostgresMetrics.ps1
```

O script usa administração de .env somente para provisionar via psql. Cria infra/.local/postgres-metrics-password com 32 bytes CSPRNG em 64 caracteres hexadecimais, sem imprimir o valor. Arquivo existente válido é reutilizado; inválido não é sobrescrito. Se usar outro nome de projeto da infraestrutura, passar -ProjectName ao provisionador e -p aos comandos Compose.

Login securegate_metrics: pg_monitor, CONNECT no banco configurado, limite de três conexões, statement_timeout=5s, search_path=pg_catalog e padrão de transação read-only. Sem superuser, createdb, createrole, replication ou bypassrls; não recebe SELECT/DML nas tabelas IAM. Script recusa papel existente com atributos/memberships/ownership ou ACLs inesperados em public/iam_* e CREATE; revisar o estado antes de repetir. A transação SQL falha integralmente, mas o arquivo privado criado antes dela permanece para recuperação.

pg_monitor permite ver estatísticas/configurações e funções de monitoração amplas do cluster, inclusive informação de sessões/SQL em pg_stat_activity. Os coletores escolhidos não exportam texto SQL. O papel não é uma sandbox: privilégios via PUBLIC, como TEMP/CONNECT/EXECUTE, continuam existentes. O padrão read-only pode ser desligado pelo cliente; ACLs são o controle de acesso às tabelas. Não alterar permissões PUBLIC dos serviços para implantar este exporter.

O script aplica a senha do arquivo ao login a cada execução; não rotaciona credenciais da aplicação. Não possui recuperação automática de senha antiga após falha de disco/SQL. Arquivos usam permissões do sistema, sem configurar ACL Windows; proteger infra/.local e não compartilhar .env ou saída completa de docker compose config.

## Exporter e coleta

Se 19187 estiver ocupada, definir POSTGRES_METRICS_PORT em .env. Usar porta livre, distinta dos demais listeners. A imagem 0.19.1 tem digest fixado; senha é montada read-only e nunca incluída na URI/ambiente do exporter. Nome do banco deve seguir o formato local suportado pelo provisionador: letras minúsculas, dígitos e underscore.

```powershell
docker compose --env-file .env -f infra/docker-compose.yml -f infra/docker-compose.postgres-metrics.yml config --quiet
docker compose --env-file .env -f infra/docker-compose.yml -f infra/docker-compose.postgres-metrics.yml up -d --no-deps postgres-exporter
docker compose --env-file infra/.local/observability/compose.env -f infra/observability/docker-compose.yml stop
.\infra\scripts\Enable-PostgresCollection.ps1 -PostgresMetricsPort 19187
.\infra\scripts\Start-Observability.ps1
```

O banco deve estar saudável antes do comando --no-deps. Só o exporter é iniciado. Os overlays RabbitMQ e exporter são independentes; em operações globais que recriem esses serviços, incluir os overlays desejados. Para outro nome da coleta, usar -p no stop e -ProjectName no launcher.

O script de inclusão exige coleta existente e cria prometheus.yml.before-postgres. Recusa duplicidade, backup existente e porta já usada nos jobs/portas da coleta. Não altera compose.env, tokens, senha Grafana ou volumes. Escritas são sequenciais, sem rollback automático por erro de disco. Porta informada deve coincidir com a publicada pelo exporter; o script não altera .env nem inicia containers.

## Verificação e limites

```promql
up{job="postgres"}
pg_up{job="postgres"}
sum(pg_stat_database_numbackends{job="postgres"})
sum(pg_stat_database_xact_commit{job="postgres"})
sum(pg_database_size_bytes{job="postgres"})
sum(pg_stat_database_deadlocks{job="postgres"})
```

Abrir **SecureGate PostgreSQL — Operação local**, UID securegate-postgres, no Grafana autenticado. Seis painéis: scrape, conexão SQL, conexões no cluster, commits acumulados, tamanho dos bancos e deadlocks acumulados.

Up=1 comprova scrape HTTP; pg_up=1 comprova conexão SQL. Credencial SQL inválida pode manter up=1 com pg_up=0. Não tratar ausência de série como zero nem concluir funcionamento de todos os coletores apenas por pg_up; verificar dados e erros dos coletores. Stats de commits incluem monitoração e outros bancos; não são throughput da aplicação. Tamanho e conexões não equivalem a limites de CPU/memória ou capacidade. Teste usa PostgreSQL 16, sem pg_stat_statements e sem consultas a tabelas IAM durante scrape.

Endpoint http://127.0.0.1:19187/metrics sem autenticação HTTP nesta configuração local. Dentro da rede Docker do banco, containers que alcançam o exporter podem consultar métricas. Conexão SQL usa sslmode=disable na rede local. Coleta remota/TLS e rotação Vault dessa credencial não estão implementadas.

## Rotação, falha e retirada

Para rotação planejada: parar somente postgres-exporter, substituir o conteúdo do arquivo privado por nova senha CSPRNG no mesmo formato, executar Provision-PostgresMetrics.ps1 e recriar o exporter com up -d --no-deps --force-recreate postgres-exporter usando os mesmos dois arquivos Compose. Não imprimir senhas em comandos. O reinício evita que conexões SQL antigas mascarem falha da credencial. O smoke encerra apenas sessões do login dedicado no banco descartável; o provisionador não encerra sessões automaticamente.

Para retirar coleta: parar coletores, restaurar prometheus.yml.before-postgres incorporando manualmente jobs acrescentados depois do backup e iniciar Start-Observability.ps1. Parar/remover somente postgres-exporter usando o overlay; não usar down --volumes na stack persistente. Opcionalmente, após parar o serviço, executar administração SQL revisada para DROP ROLE securegate_metrics e apagar seu arquivo privado. Não remover pg_monitor nem modificar logins IAM.

## Validação opt-in

```powershell
.\mvnw.cmd -B -ntp -pl integration-tests -am spotless:apply '-Dtest=NoSelectedUnitTests' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=PostgresObservabilitySmoke' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djacoco.skip=true' verify
```

Smoke usa Compose/provisionamento versionados em workspace isolado, PostgreSQL/exporter/Prometheus/Grafana reais. Verifica atributos, ACLs com read-only desligado, recusa de grant inesperado, transações e dados no Grafana, ausência de payload/SQL no scrape, credencial SQL inválida com HTTP disponível e recuperação. IAM/enforcer não são inicializados neste smoke. [Resultados e limitações](../evidencias/etapa-10b2b2b.md).

A [coleta Redis 10B2b2c](metricas-redis-local.md) usa outro overlay e ACL própria, sem modificar o login PostgreSQL. Próxima dependência: Vault.

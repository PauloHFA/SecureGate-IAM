# Métricas locais Redis — 10B2b2c

## Preparar ACL sem alterar o serviço ativo

Requisitos: Redis local saudável conforme [infra](../../infra/README.md), .env preparado, Docker Desktop/Windows, PowerShell e coleta [10B2a](coleta-iam-local.md) existente. Comandos na raiz. Esta entrega foi validada em projetos descartáveis, sem aplicar mudanças à stack persistente do usuário.

```powershell
.\infra\scripts\Initialize-RedisMetrics.ps1
```

Para outro nome de projeto da infraestrutura, usar -ProjectName no preparador e -p nos comandos Compose. O preparador autentica como default com .env via REDISCLI_AUTH temporário, verifica ACL USERS/GETUSER e restaura o ambiente do host. Nenhuma senha/hash/ACL completa é impressa.

Aceita apenas o default do contrato base e, opcionalmente, securegate_metrics com os comandos previstos, sem keys/channels/selectors adicionais. Nomes de usuários são sensíveis a maiúsculas: DEFAULT não é default e é recusado como usuário extra. A URI do mapa também é comparada exatamente. Usuários extras ou permissões inesperadas causam recusa antes de preparar arquivos. Se houver ACL customizada, integrar monitoração ao desenho existente separadamente; não apagar usuários para fazer o script passar.

Arquivos ignorados em infra/.local:

| Arquivo | Conteúdo |
| --- | --- |
| redis-metrics-password.json | Mapa com entrada fixa redis://securegate_metrics@redis:6379 e senha aleatória de 64 hex; a versão fixada inclui o usuário no lookup. |
| redis-metrics-users.acl | Default com hash da senha atual de .env e usuário metrics com hash próprio. |

Os dois arquivos devem existir juntos, com formato/conteúdo esperado. Reexecução válida preserva os bytes; divergência não é sobrescrita. Escritas CreateNew são sequenciais: falha de disco pode deixar preparação parcial, que precisa de revisão explícita. Proteger ambos com permissões do sistema; o script não configura ACL Windows. Não versionar nem compartilhar arquivos privados ou saída completa de docker compose config.

## Aplicar overlay e incluir a coleta

Escolher janela para reiniciar Redis: há interrupção das conexões da aplicação. AOF/volume são preservados. O usuário default continua com a mesma senha/permissões; a senha passa a vir do ACL file, não de CONFIG requirepass. O preparador não reinicia nem aplica ACL ativa sozinho.

Se 19121 estiver ocupada, definir REDIS_METRICS_PORT em .env com porta livre distinta dos outros listeners e informar a mesma porta ao script de coleta.

```powershell
docker compose --env-file .env -f infra/docker-compose.yml -f infra/docker-compose.redis-metrics.yml config --quiet
docker compose --env-file .env -f infra/docker-compose.yml -f infra/docker-compose.redis-metrics.yml up -d --no-deps --wait --wait-timeout 90 redis
docker compose --env-file .env -f infra/docker-compose.yml -f infra/docker-compose.redis-metrics.yml up -d --no-deps redis-exporter
docker compose --env-file infra/.local/observability/compose.env -f infra/observability/docker-compose.yml stop
.\infra\scripts\Enable-RedisCollection.ps1 -RedisMetricsPort 19121
.\infra\scripts\Start-Observability.ps1
```

Incluir o overlay nas operações futuras que recriem Redis para manter ACL/monitoração. Overlays PostgreSQL/RabbitMQ são independentes; em operações globais incluir todos os desejados. O Compose base não inicia exporter. Bind mounts recusam arquivos ausentes, sem criar diretórios no lugar de senhas.

O script de coleta cria prometheus.yml.before-redis e recusa duplicidade, backup existente e colisão com portas dos jobs/coleta. Não modifica compose.env, senha Grafana ou tokens. Executar com coletores parados; escrita sem rollback automático em erro de disco. Para outro projeto de observabilidade, usar -p no stop e -ProjectName no launcher.

## Acesso, métricas e diagnóstico

securegate_metrics permite somente INFO, PING, CLIENT SETNAME, COMMAND INFO e SLOWLOG LEN, sem padrões de chaves/canais. Não permite ler/gravar dados, listar chaves, CONFIG, ACL, EVAL, FLUSHDB ou SLOWLOG GET. O exporter usa INFO para estatísticas e keyspace agregado, sem percorrer chaves.

**SLOWLOG GET é tentado pelo exporter e recusado deliberadamente.** Há registros NOPERM em ACL LOG e ausência das métricas do último comando lento; isso não implica falha de INFO. Não conceder GET de logs nem habilitar debug para ocultar esse diagnóstico. INFO expõe estatísticas/configuração técnica do servidor, não é uma sandbox de informações operacionais.

```promql
up{job="redis"}
redis_up{job="redis"}
redis_exporter_last_scrape_error{job="redis"}
redis_memory_used_bytes{job="redis"}
redis_connected_clients{job="redis"}
sum(redis_db_keys{job="redis"})
redis_commands_processed_total{job="redis"}
```

Grafana: **SecureGate Redis — Operação local**, UID securegate-redis, seis painéis. Up verifica HTTP; redis_up verifica conexão/INFO. Senha inválida pode manter up=1 com redis_up=0 e last_scrape_error=1. Não interpretar ausência de série como zero. Conexões/comandos incluem monitoração e administração; chaves agregadas não mostram marcas por usuário ou SLA de revogação. Memória informada por INFO não é limite de container nem capacidade sustentada.

Endpoint http://127.0.0.1:19121/metrics sem autenticação HTTP nesta configuração. Containers que alcançam a rede Docker Redis também podem alcançar o exporter. Tráfego local sem TLS. Endpoint /scrape para alvos arbitrários está desabilitado; não adicionar flags de chaves/valores/CLIENT LIST/scripts para esta ACL.

## Reinício, rotação e retirada

ACL ativa é recarregada do arquivo no startup. Não usar ACL SAVE ou CONFIG REWRITE (arquivo read-only) e não presumir persistência de alterações feitas por ACL SETUSER em memória. O smoke reinicia Redis e confirma a permanência do usuário metrics, do acesso default e dos dados AOF.

Para rotação planejada: parar exporter, fazer backup privado dos dois arquivos, retirar o par dos caminhos esperados e executar o preparador contra Redis ainda ativo. Ele gera nova senha/ACL mantendo a senha default de .env. Aplicar novo arquivo recriando Redis e exporter, em janela de interrupção. Só executar se a ACL ativa continuar compatível com o contrato verificado pelo preparador. Não alterar senha default/.env isoladamente; rotação da aplicação exige coordenação própria. Backups contêm credenciais/hashes e não devem ser publicados.

Para retirar coleta: parar coletores, restaurar prometheus.yml.before-redis incorporando jobs posteriores e iniciar Start-Observability.ps1. Parar/remover somente redis-exporter usando o overlay; depois recriar somente Redis com Compose base, mantendo AOF/volume e senha default. Isso remove o usuário metrics no reinício. Não usar down --volumes na stack persistente. Conservar os arquivos privados/backups até confirmar recuperação.

## Validação opt-in

```powershell
.\mvnw.cmd -B -ntp -pl integration-tests -am spotless:apply '-Dtest=NoSelectedUnitTests' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=RedisObservabilitySmoke' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djacoco.skip=true' verify
```

Smoke usa Redis/exporter/Prometheus/Grafana reais e arquivos versionados em workspace isolado. Exige recusa de ACL customizada, comandos de chaves/administrativos, preservação de arquivos divergentes, dados/ACL após reinício, seis consultas/frame Grafana, falha de credencial e recuperação, artefatos sem dados/segredos. IAM/enforcer não são iniciados neste smoke. [Resultados e limitações](../evidencias/etapa-10b2b2c.md).

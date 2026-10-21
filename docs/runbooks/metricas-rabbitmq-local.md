# Métricas locais do RabbitMQ — 10B2b2a

## Preparação

Requisitos: infraestrutura local preparada por Initialize-Local.ps1 e coleta IAM preparada conforme [10B2a](coleta-iam-local.md). O plugin é nativo da imagem já usada; nenhum exporter externo é instalado. Comandos na raiz, Windows/Docker Desktop. Esta entrega não inicia nem reconfigura automaticamente a infraestrutura persistente do usuário.

1. Escolher uma janela para recriar somente o broker. O volume, as definições e as credenciais são preservados; as conexões AMQP serão interrompidas.
2. Se 15692 estiver ocupada, acrescentar RABBITMQ_METRICS_PORT a .env com uma porta livre, distinta de AMQP, management, IAM/enforcer, Prometheus e Grafana. O exemplo usa 15692.
3. Aplicar o overlay opcional com o mesmo nome de projeto da infraestrutura existente (padrão securegate-iam):

```powershell
docker compose --env-file .env -f infra/docker-compose.yml -f infra/docker-compose.rabbitmq-metrics.yml config --quiet
docker compose --env-file .env -f infra/docker-compose.yml -f infra/docker-compose.rabbitmq-metrics.yml up -d --no-deps --wait --wait-timeout 120 rabbitmq
```

O Compose padrão continua sem publicação de métricas. Nos próximos comandos que recriarem o broker, incluir ambos os arquivos para manter o plugin.

## Adicionar à coleta existente

Parar somente os coletores, sem remover volumes. Se usar outro nome de projeto de observabilidade, acrescentar -p em todos os comandos e -ProjectName ao launcher. O overlay do enforcer, quando usado, não altera este comando stop.

```powershell
docker compose --env-file infra/.local/observability/compose.env -f infra/observability/docker-compose.yml stop
.\infra\scripts\Enable-RabbitMqCollection.ps1 -RabbitMqMetricsPort 15692
.\infra\scripts\Start-Observability.ps1
```

O script exige preparação prévia, recusa job RabbitMQ existente, porta já usada na coleta ou backup existente e cria prometheus.yml.before-rabbitmq antes da escrita. Não altera compose.env, senha Grafana ou tokens IAM/enforcer. A porta informada deve coincidir com a publicada pelo broker; o script não consulta nem modifica .env. As escritas são sequenciais e não possuem rollback automático em erro de disco; revisar o backup antes de tentar de novo.

## Verificação e interpretação

Em Prometheus, consultar up{job="rabbitmq"}: deve ser 1. Em Grafana, abrir **SecureGate RabbitMQ — Operação local**, UID securegate-rabbitmq, após autenticar com a senha privada existente. Dashboard tem seis painéis e não exige o listener do enforcer.

| Série | Leitura |
| --- | --- |
| rabbitmq_queue_messages_ready | Mensagens disponíveis para entrega, agregadas no nó. |
| rabbitmq_queue_messages_unacked | Mensagens entregues que aguardam ACK. |
| rabbitmq_connections / rabbitmq_channels | Conexões/canais abertos no nó. |
| rabbitmq_queue_consumers | Consumidores registrados nas filas. |

Coleta a cada 15 s; estatísticas do broker têm sua própria atualização. Totais incluem filas de retry e DLQ: não associar todos os valores a uma fila ou consumidor específico. Antes da primeira coleta, ausência de série não significa zero. Up=1 comprova scrape; não comprova entrega/ACK ou saúde da aplicação. Up=0 exige verificar plugin, container, porta e host.docker.internal.

O endpoint http://127.0.0.1:15692/metrics é leitura sem autenticação nesta configuração local. Não fornecer senha AMQP nem token IAM ao scraper. A porta externa fica em loopback; na rede Docker, o listener do broker é acessível aos containers que alcançam essa rede. O plugin também disponibiliza endpoints por objeto: o job usa somente métricas agregadas /metrics. Não publicar o listener em outra interface para coleta remota.

## Retirada e recuperação

Parar coletores, restaurar prometheus.yml.before-rabbitmq sobre prometheus.yml e iniciar Start-Observability.ps1. O backup representa a configuração no momento da migração: incorporar manualmente jobs adicionados posteriormente antes de restaurar. Não apagar tokens ou senha Grafana. Remover o backup somente após revisar a recuperação.

Para retirar o listener, recriar apenas RabbitMQ com o Compose base:

```powershell
docker compose --env-file .env -f infra/docker-compose.yml up -d --no-deps --wait --wait-timeout 120 rabbitmq
```

Não usar down --volumes na infraestrutura persistente. No smoke, volumes são removidos somente nos dois projetos descartáveis com nomes gerados pelo teste.

## Validação opt-in

```powershell
.\mvnw.cmd -B -ntp -pl integration-tests -am spotless:apply '-Dtest=NoSelectedUnitTests' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=RabbitMqObservabilitySmoke' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djacoco.skip=true' verify
```

O smoke usa broker real com configuração/overlay versionados, cria fila exclusiva e verifica ready/unacked/ACK, seis expressões, frame Grafana, acesso anônimo negado e pausa/recuperação do scrape. O IAM não é iniciado: seu job fica up=0, esperado nesta fixture isolada. Credenciais aleatórias de teste não são reutilizadas no projeto. Não é ensaio sustentado ou teste fim a fim de IAM/enforcer. [Evidências e limitações](../evidencias/etapa-10b2b2a.md).

A [coleta PostgreSQL 10B2b2b](metricas-postgres-local.md) é um overlay separado, com login dedicado e senha por arquivo; não modifica a configuração RabbitMQ. A [coleta Redis](metricas-redis-local.md) está na 10B2b2c; próxima dependência: Vault.

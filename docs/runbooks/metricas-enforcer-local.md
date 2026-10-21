# Runbook — Métricas e coleta do enforcer (10B2b1)

## Listener opcional

O Spring do enforcer continua non-web. Sem flag, nenhum listener de métricas é criado. Com a flag, o módulo jdk.httpserver do Java 21 atende somente GET /metrics em 127.0.0.1, padrão 19091. Não adiciona MVC, rotas de negócio ou endpoints Actuator. Java 21 completo e Windows/Docker Desktop são a base exercitada.

Na primeira preparação, na raiz:

```powershell
if (-not (Test-Path -LiteralPath infra/.local/enforcer-metrics-token)) {
  .\infra\scripts\Initialize-MetricsToken.ps1 -Target Enforcer
}
.\infra\scripts\Start-PolicyEnforcer.ps1 -EnableConsumer -EnableMetrics
```

Acrescentar EnableVaultKv se já usa esse modo. EnableMetrics não implica consumidor/Vault e não muda credenciais de broker/Redis. Sem EnableConsumer, o scrape pode estar up com métricas JVM, mas isso não comprova consumo. O token próprio fica em arquivo ignorado; não imprimir/reutilizar o token IAM. O gerador padrão sem Target continua preparando o IAM.

| Configuração | Padrão/contrato |
| --- | --- |
| ENFORCER_METRICS_ENABLED | false; cria listener somente quando true. |
| ENFORCER_METRICS_PORT | 19091, permitido 1024–65535; flag MetricsPort no launcher. |
| ENFORCER_METRICS_TOKEN_FILE | Arquivo privado, regular, até 512 bytes, token de 32–256 caracteres após trim; sem espaços/controles internos. |

O digest do token é carregado no startup; rotação exige reinício do enforcer. Arquivo inválido/ausente ou porta indisponível falha startup com erro genérico sem valor/caminho da credencial. Inicializador recusa sobrescrita e não configura ACL. Proteger permissões locais. Listener usa dois workers, fila de 16 e backlog 16; não é limite de taxa nem capacidade validada. Encerramento para servidor/executor; teste exige recusa de conexão após close.

## Coleta nova

Preparar token IAM e Grafana conforme [10B2a](coleta-iam-local.md). Antes da primeira inicialização de configuração, incluir o enforcer:

```powershell
.\infra\scripts\Initialize-Observability.ps1 -IncludeEnforcer
.\infra\scripts\Start-Observability.ps1
```

Porta customizada deve coincidir: MetricsPort no processo e EnforcerPort no inicializador. Todas as portas devem ser distintas. O launcher seleciona automaticamente o overlay docker-compose.enforcer.yml quando compose.env contém OBS_ENFORCER=true. Só esse modo monta o token do enforcer; coleta IAM-only continua sem exigir esse arquivo.

## Acrescentar à configuração 10B2a existente

Não repetir o inicializador para substituir senha/volumes. Parar somente coletores, preservando dados:

```powershell
docker compose --env-file infra/.local/observability/compose.env -f infra/observability/docker-compose.yml down
.\infra\scripts\Enable-EnforcerCollection.ps1
.\infra\scripts\Start-Observability.ps1
```

Se usou ProjectName customizado, passar o mesmo -p na parada e -ProjectName no startup. A migração valida token/porta, cria prometheus.yml.before-enforcer e compose.env.before-enforcer privados e acrescenta job/flag, preservando senha Grafana e volumes. Recusa job/backup existentes e colisão de porta. Não inicia/para serviços automaticamente. Não é uma transação entre arquivos: em falha parcial, revisar/restaurar os backups com coletores parados antes de repetir. Nenhum segredo novo é escrito no YAML. Migração inversa exige restaurar os dois arquivos de backup, sem apagar volumes/credenciais.

## Dashboard e diagnósticos

No Grafana local, pasta SecureGate, dashboard **SecureGate Enforcer — Operação local**, UID securegate-enforcer. Há seis painéis: scrape up, mensagens de revogação, mensagens de bloqueio/política, erros de conexão por worker, heap e CPU. Dashboard pode aparecer sem séries se a coleta opcional não estiver habilitada. Mensagens só aparecem após eventos; rate de 5 min exige histórico e pode ser zero mesmo quando o contador acumulado mostra um evento anterior. Não preencher ausência de séries com zero.

| Resposta/sinal | Interpretação |
| --- | --- |
| GET /metrics 200 | Token correto; registro JVM/customizado exportado, sem sessão/cookie. |
| 401 | Ausente/incorreto/duplicado ou token de outro processo; não prova falha no broker. |
| 405 com token correto | Método diferente de GET; header Allow: GET. |
| 404 com token correto | Caminho diferente de /metrics, inclusive Actuator; nenhuma rota de negócio. |
| 400 com token correto | Query não permitida; filtro de série não é suportado nessa rota. |
| up=0 | Conferir alvo/porta/token, listener, Docker Desktop; não ampliar bind como correção. |
| up=1 e contador ausente | Conferir EnableConsumer, fila/eventos e histórico; scrape não é atestado de progresso. |

A credencial IAM não foi aceita pelo listener do enforcer no smoke. A troca deliberada do arquivo do scraper causou 401/up=0 só nesse job, mantendo IAM up; restauração do token original recuperou coleta sem reiniciar aplicação. Isso não é rotação de credenciais. Não há coleta nativa de fila/banco/Redis/Vault nesta entrega, só sinais internos já emitidos pelos workers.

## Validação

```powershell
.\mvnw.cmd -B -ntp -pl policy-enforcer -am verify
.\mvnw.cmd -B -ntp -pl integration-tests -am '-Dtest=NoSelectedUnitTests' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=EnforcerObservabilitySmoke,ObservabilitySmoke' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djacoco.skip=true' verify
```

O primeiro executa módulo/dependência, arquitetura, cobertura e integrações. Os smokes são opt-in, usam JARs/serviços reais e Compose copiado em projeto aleatório, sem stack persistente. O novo teste produz usuário/bloqueio por API, exige fato user.locked publicado/processado, projeção LOCKED no Redis, ACK exportado, consultas/frame Grafana e controle de token. Não fabrica contadores no smoke; o teste unitário isolado usa contador sintético para testar formato/autenticação. Finalização remove somente recursos/arquivos de teste; interrupção abrupta pode exigir limpeza local.

Resultados concretos: [evidências](../evidencias/etapa-10b2b1.md). Plataforma Linux, QA visual, carga sustentada, rate limiting/TLS e SLA não foram validados. A [10B2b2a](metricas-rabbitmq-local.md) acrescenta coleta RabbitMQ opcional. A [10B2b2b](metricas-postgres-local.md) acrescenta PostgreSQL; a [10B2b2c](metricas-redis-local.md) acrescenta Redis; próxima entrega: **10B2b2d — Vault**, depois 10B3 logs/tracing.

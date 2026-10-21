# Runbook — Prometheus/Grafana do IAM (10B2a)

## Escopo e preparação

Windows, Docker Desktop com host.docker.internal e Docker Compose com up --wait. Versão exercitada do Compose: 5.6.0. IAM executa no host com bind loopback; esse acesso pelo Docker Desktop foi comprovado no smoke. Linux/PowerShell 7 não foi validado. Não alterar bind do IAM para 0.0.0.0 como procedimento de correção deste guia.

O Compose [infra/observability/docker-compose.yml](../../infra/observability/docker-compose.yml) é independente da stack de negócio. Não sobe/reconfigura PostgreSQL/Redis/RabbitMQ/Vault/IAM, não lê .env e não habilita exposição do enforcer. Preparar a infraestrutura e o build do IAM conforme [infra](../../infra/README.md) e [10B1](metricas-prometheus-local.md).

Na primeira preparação, na raiz:

```powershell
if (-not (Test-Path -LiteralPath infra/.local/iam-metrics-token)) {
  .\infra\scripts\Initialize-MetricsToken.ps1
}
.\infra\scripts\Initialize-Observability.ps1
```

São gerados em infra/.local/observability: prometheus.yml (alvo do IAM), compose.env (somente portas) e grafana-admin-password (CSPRNG de 32 bytes/64 hex). A configuração de scrape referencia o token IAM por credentials_file; nenhum segredo é interpolado no YAML, dashboard ou argumentos. O Grafana lê a senha via GF_SECURITY_ADMIN_PASSWORD__FILE. Diretórios ignorados não substituem permissões do sistema; scripts não ajustam ACL.

O inicializador recusa arquivos existentes. Falha parcial pode deixar arquivos; revisar antes de tentar novamente. Para portas diferentes, usar -IamPort, -PrometheusPort e -GrafanaPort, todas distintas e entre 1024–65535. Não modificar secret/portas de uma stack em uso apenas para repetir o smoke.

## Iniciar e acessar

Terminal do IAM, acrescentando as flags de negócio necessárias à mesma instância:

```powershell
.\infra\scripts\Start-IamCore.ps1 -EnablePrometheus -EnableHttpHistograms
```

Outro terminal:

```powershell
.\infra\scripts\Start-Observability.ps1
```

Padrões: Prometheus em http://127.0.0.1:19090 e Grafana em http://127.0.0.1:13000. Faça login no Grafana com usuário admin e senha do arquivo privado infra/.local/observability/grafana-admin-password; não é admin/admin. Acesse a pasta SecureGate, dashboard **SecureGate IAM — Operação local**, UID securegate-iam. Não cole a senha em logs/issues. A API de saúde do Grafana é pública; dashboard/datasource exigem login.

O datasource provisionado usa UID securegate-prometheus e acessa http://prometheus:9090 na rede Compose. O dashboard é versionado/gerenciado por arquivo, sem edição persistida pela UI. Para mudanças, editar o JSON versionado e revisar/testar; atualizar UI não substitui o fonte.

Verificar a consulta up{job="iam-core"}: deve retornar 1. Containers saudáveis atestam seus processos, não sucesso do scrape. Em Prometheus → Targets, IAM precisa estar up. O Prometheus local não tem autenticação de consulta; fica publicado apenas em loopback, sem API administrativa/lifecycle habilitada. Não expor essas portas à rede como solução de conectividade.

## Painéis e interpretação

Doze painéis: coleta, taxa HTTP, p95 HTTP, 5xx, heap, CPU, conexões do pool, backlog/quarentena Outbox, idade/freshness, disponibilidade do snapshot, mensagens de auditoria e falhas do enforcement. Actuator é excluído das consultas HTTP de negócio. Painéis opcionais dependem de flags/eventos; sem série não significa zero. HTTP p95 exige buckets e amostras suficientes; janelas de 5 min não são um ensaio de SLA por si sós.

O painel de coleta e o de snapshot usam 0 vermelho/indisponível e 1 verde/disponível. Conferir disponibilidade/freshness antes de interpretar backlog. O [dashboard opcional do enforcer](metricas-enforcer-local.md) está na 10B2b1; o modo IAM-only não inclui seu job. Exporters de dependências, alertas e tracing não são entregues aqui. Ver [inventário](../OBSERVABILIDADE.md).

Scrape a cada 5 s, timeout 3 s. Cada container tem limite de memória de 512 MiB; não é capacidade medida. TSDB configurada com retenção 24 h/256 MB, sem ensaio de retenção; WAL/outros arquivos podem consumir espaço adicional. Dados Grafana e Prometheus persistem em volumes próprios.

## Diagnóstico e credenciais

| Sintoma | Verificação |
| --- | --- |
| up=0 com erro 401 | Conferir token de leitura e digest carregado no IAM; não substituir por bootstrap/admin. |
| up=0 com conexão recusada/timeout | Conferir IAM/porta, Docker Desktop/host.docker.internal e firewall local; não relaxar autorização. |
| up=1, painel sem dados | Produzir tráfego de negócio, conferir flag/labels, histogramas e histórico da janela. |
| Grafana sem dashboard/datasource | Conferir mounts/provisioning e UID; health isolado não é aceite da configuração. |
| Inicialização recusa arquivo existente | Preserva configuração/credenciais; não sobrescrever nem descartar volumes como tentativa automática de reparo. |

A senha Grafana configura a conta inicial no primeiro banco; trocar o arquivo/reiniciar com volume existente não garante mudança da senha já gravada. Rotação deve usar o fluxo autenticado do Grafana. Token IAM é independente e requer reinício do IAM se o valor esperado mudar. O controle do teste apenas restaura o token original após uma troca deliberadamente inválida; não é validação de rotação de credenciais.

## Parar sem apagar dados

```powershell
docker compose --env-file infra/.local/observability/compose.env -f infra/observability/docker-compose.yml down
```

Se usou -ProjectName no launcher, passar o mesmo -p ao Compose. Não adicionar --volumes para uma parada normal. O smoke usa projeto aleatório exclusivo e remove apenas seus containers/volumes descartáveis; nenhum serviço persistente de negócio é parado por ele.

## Validação isolada

```powershell
.\mvnw.cmd -B -ntp -pl integration-tests -am '-Dtest=NoSelectedUnitTests' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=ObservabilitySmoke' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djacoco.skip=true' verify
```

O nome Smoke é opt-in; build padrão não baixa Prometheus/Grafana nem executa esta classe. O teste copia os arquivos versionados e scripts para target, usa IAM/JARs reais e serviços descartáveis, portas livres e projeto próprio. Confere coleta, JVM/buckets, 12 painéis provisionados, parsing real de todas as expressões PromQL, frame retornado pelo datasource Grafana, anonimato recusado e controle 401→up=0→recuperação. Remove credenciais em finally; encerramento abrupto pode deixar arquivos/stack do teste e exige revisão local. Não faz QA visual no navegador, teste de todos os painéis com dados nem carga sustentada.

Resultados e comandos exatos: [evidências](../evidencias/etapa-10b2a.md). A [10B2b2a](metricas-rabbitmq-local.md) acrescenta coleta RabbitMQ opcional. Próxima entrega: **10B2b2b — PostgreSQL**, seguida por Redis/Vault; depois 10B3 logs/tracing e 10C medição.

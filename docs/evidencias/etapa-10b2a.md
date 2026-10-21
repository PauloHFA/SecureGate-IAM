# Evidências — Etapa 10B2a: coleta e dashboard IAM

- Data: 21/10/2026; horários UTC−03.
- Base: 9f5f857 (10B1); branch feat/iam-observability-stack; diretório limpo ao iniciar.
- Estado: implementação local, incluída neste commit; push/CI remota pendentes.

## Entrega e divisão

10B2 foi dividida em 10B2a (IAM) e 10B2b (enforcer/dependências). Compose independente com Prometheus/Grafana em loopback, rede/volumes próprios, config de scrape gerada sem segredo inline, token IAM somente leitura e senha Grafana CSPRNG por arquivo. Datasource e dashboard de 12 painéis provisionados. Dois scripts preparam/iniciam; não alteram .env nem a stack de negócio.

Runtime Java, fixture existente, schema V22, grants SQL e ACL Vault não foram modificados. A única classe Java nova é smoke opt-in. Nenhum exporter/enforcer HTTP, alerta, tracing/logging estruturado ou coleta sustentada entregue. 10B/etapa 10 continuam sem aceite global.

## Ambiente, versões e imagens

Windows/PowerShell 5.1, Java 21, Docker Desktop e Compose **5.6.0**. Releases conferidas por API oficial GitHub em 21/10/2026 e imagens efetivamente baixadas; Compose fixa também os digests:

| Imagem | Digest validado |
| --- | --- |
| prom/prometheus:v3.15.0 | sha256:efd719c99d83b060d9daefdcf00360461adf279f45ef5391f8d111892118753e |
| grafana/grafana:13.2.4 | sha256:dbbc38ea21103cefb50396340cd88afec779c7e070b0a7bf5a75b4ce46651d3f |

IAM/enforcer JARs reais, PostgreSQL 16.15-alpine, Redis 7.4.11-alpine, RabbitMQ 4.3.6-management-alpine e Vault 1.21.4 descartáveis da fixture. Prometheus acessou IAM ligado a 127.0.0.1 no host via host.docker.internal; não foi necessário ampliar bind do IAM. Grafana acessou Prometheus pelo DNS interno do Compose.

## Comando e resultado

```powershell
.\mvnw.cmd -B -ntp -pl integration-tests -am spotless:apply '-Dtest=NoSelectedUnitTests' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=ObservabilitySmoke' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djacoco.skip=true' verify
```

**BUILD SUCCESS às 12:12:43**, duração Maven **1 min e 31 s**, **1 teste** (80,20 s), zero falhas/erros/ignorados. Spotless, compilação/empacotamento e Failsafe da seleção aprovados. O primeiro smoke também passou; depois foram acrescentados validação de todas as expressões/frame Grafana, cores de disponibilidade e cleanup em finally, e a seleção foi repetida com o resultado acima.

O comando final explicitou exit $LASTEXITCODE no PowerShell para preservar o exit code nativo e evitar a ambiguidade da captura de stderr observada na 10B1. Log ignorado .firecrawl/etapa10b2a-smoke.log. Nenhum clean verify foi repetido nesta entrega de scripts/configurações/teste opt-in; os **782 testes** e a ressalva de encerramento JVM/Hikari continuam como resultado histórico da [10B1](etapa-10b1.md), não são somados nem declarados reexecutados/resolvidos. JaCoCo desativado na seleção focalizada.

## Contratos comprovados

| Verificação | Resultado |
| --- | --- |
| Preparação e sobrescrita | Senha CSPRNG de 64 hex; segunda inicialização recusada sem modificar senha. |
| Compose versionado | Mesmos arquivos/scripts copiados para workspace descartável; containers saudáveis com up --wait. |
| Scrape real | up{job="iam-core"}=1; séries JVM e buckets HTTP recebidas. |
| Dashboard/datasource | API devolveu 12 painéis e meta.provisioned=true; datasource health=OK. |
| Expressões | Todas as 14 expressões dos 12 painéis aceitas por Prometheus real; séries opcionais vazias não tratadas como zero. |
| Consulta via Grafana | /api/ds/query retornou frame real da métrica JVM usando datasource provisionado. |
| Acesso anônimo | API de dashboard sem credencial recebeu 401. |
| Controle de credencial | Arquivo do scraper trocado por valor inválido: up=0 e lastError com 401 do IAM; restauração do token original recuperou up=1. |
| Artefatos | Busca por token IAM, senha/Basic Grafana e token administrativo da fixture fora da pasta privada: zero correspondências. |
| Limpeza | Compose down --volumes removeu apenas projeto aleatório securegate-obs-test-*; arquivos dos dois segredos removidos em finally. |

Nenhuma credencial .env foi usada pela fixture/coletor, nenhum serviço persistente do usuário foi iniciado/parado/modificado. Imagens baixadas permanecem no cache Docker; dados/containers do teste foram removidos. Logs e cópia dos arquivos/result.json em integration-tests/target/observability-smoke/<execução>, ignorados. Não houve QA visual do navegador; API/provisionamento/queries foram exercitados.

## Revisão documental e limites

Parsers PowerShell e JSON do dashboard aprovados, git diff --check sem erros; 1149 links Markdown locais válidos e 52 registros de etapas referenciados no guia. Comparação em memória dos 19 arquivos alterados com cinco valores sensíveis locais: zero correspondências, sem imprimir valores. Exit code nativo final confirmado como 0. Consulta Docker após o teste não encontrou containers securegate-obs-test remanescentes.

Não há painel com dados garantidos para cada integração opcional: auditoria/enforcement exigem suas flags/eventos. Dashboard HTTP exige tráfego fora do Actuator e histórico para rate/p95; não houve ensaio de 5 minutos, capacidade/300 req/s, estabilidade, alertas ou retenção. Limites configurados: scrape 5 s/timeout 3 s, 512 MiB por container, TSDB 24 h/256 MB (WAL/outros arquivos adicionais). Não são medições de uso máximo.

API Prometheus sem autenticação de consulta permanece apenas em loopback; Grafana usa senha própria e token IAM não concede administração. Senha inicial Grafana é persistida no banco, não é rotacionada automaticamente pela troca do arquivo. Segredos locais exigem permissões; scripts não ajustam ACL. Interrupção abrupta pode deixar stack/arquivos de teste para revisão manual. Windows/Docker Desktop foi exercitado; Linux/PowerShell 7 e coleta remota/TLS não foram validados.

Próxima implementação: **10B2b — enforcer e dependências**, depois 10B3 logs/tracing. Publicação/CI/merge e aceite global da etapa 10 continuam pendentes.

Referências: [operação](../runbooks/coleta-iam-local.md), [Compose](../../infra/observability/README.md), [inventário](../OBSERVABILIDADE.md), [ADR-051](../adr/051-coleta-e-dashboard-local-do-iam.md), [passo a passo](../GUIA-DE-IMPLEMENTACAO.md).

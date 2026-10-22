# Backend local de tracing — 10B4a

## Preparação e ativação

1. Usar Docker Desktop/Compose e a preparação existente de [Prometheus/Grafana](coleta-iam-local.md). Não executar Initialize-Observability novamente numa stack preparada: ele recusa sobrescrever arquivos privados. Esta entrega não exige gerar/trocar credenciais.
2. Conferir que 13200 (API Tempo) e 14318 (OTLP/HTTP) estão disponíveis e distintas das portas de Prometheus/Grafana. Para customizar, definir TEMPO_HTTP_PORT/TEMPO_OTLP_PORT no processo que inicia a stack, entre 1024 e 65535.
3. Revisar o [overlay](../../infra/observability/docker-compose.tracing.yml) e a [configuração](../../infra/observability/tracing/tempo.yml). São locais, single-binary, dados em volume próprio e listeners publicados somente em 127.0.0.1. Tempo não autentica acesso direto; outros processos locais e containers na rede podem consultar/enviar dados.
4. Iniciar na janela de alteração do ambiente. O comando abaixo modifica a stack de observabilidade configurada e pode recriar Grafana por acrescentar o mount do datasource; não altera a stack de negócio. Não foi executado contra a stack persistente do usuário nesta entrega.

```powershell
.\infra\scripts\Start-Observability.ps1 -IncludeTracing
# Usar -ProjectName somente para o projeto que foi preparado, como no procedimento existente.
```

5. Repetir -IncludeTracing nas próximas inicializações que devam incluir o backend; o switch não grava preferência no compose.env. O script valida Compose/portas antes de up e aguarda /ready do Tempo pelo host. A imagem não possui shell; não usar wget dentro dela como healthcheck. Running não atesta ingestão/exportação.
6. Abrir Grafana com o login/senha privada existentes. O datasource adicional SecureGate Tempo, UID securegate-tempo, aponta para http://tempo:3200 pelo proxy do Grafana; SecureGate Prometheus continua padrão. Sem credencial, as APIs de datasource permanecem protegidas. Não copiar credencial Grafana para Tempo/OTLP.
7. A etapa 10B4a **não configura exporter no IAM/enforcer**. Não esperar spans de produção nessa instalação; os perfis http-tracing/consumer-tracing ainda criam spans sem envio externo. A exportação OTLP será a 10B4b.

## Armazenamento, acesso e limites

Imagem Tempo 3.1.1 fixada por digest, UID 10001, root filesystem read-only, /tmp em tmpfs e /var/tempo em volume tempo-data. Limites de memória/CPU/PIDs/logs e ingestão estão no arquivo versionado. Retenção de blocos configurada em 24h com varredura de 5 min; exclusão não é instantânea e não há quota rígida de disco. Monitorar espaço do host. O teste não espera 24h nem mede capacidade.

Não publicar portas fora de loopback nem tratar autenticação Grafana como proteção das portas diretas Tempo. Destinado a avaliação em host/rede Docker confiáveis. Não habilitar exporter de aplicação sem revisar os dados permitidos e o destino na 10B4b. Sem MCP, metrics-generator, relatórios de uso ou logging de spans recebidos/descartados. Não colocar senha/token/identidade nos spans sintéticos de diagnóstico.

## Verificação reproduzível

```powershell
# Seleção do backend: não mede cobertura das aplicações.
.\mvnw.cmd -B -ntp -pl integration-tests -am verify '-Dtest=NoUnitTestsSelected' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=TracingBackendIT' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djacoco.skip=true'
.\mvnw.cmd -B -ntp verify
```

TracingBackendIT renderiza os Composes versionados, sobe Tempo/Grafana descartáveis com configs versionadas, envia OTLP JSON sintético e consulta traces/links por API direta e proxy Grafana autenticado. Reinicia somente o backend de teste, mantendo volume, e verifica consulta posterior. Grafana sem credencial é recusado; Prometheus continua datasource padrão. A fixture monta a configuração Tempo read-only, em vez de copiá-la numa raiz read-only.

Readiness/configuração e dados sintéticos consultáveis são verificados; navegação visual Explore, aplicação real exportando, falha abrupta/perda de energia, expurgo após 24h e coleta sustentada ficam fora deste aceite. Aceitação HTTP OTLP e consulta de traces específicos não constituem garantia geral de durabilidade contra qualquer falha. [Evidências](../evidencias/etapa-10b4a.md).

## Parada e reversão

Usar o projeto/env e a mesma lista de overlays configurados, incluindo docker-compose.tracing.yml, ao parar a stack. Não usar --volumes para desabilitar: o volume preserva traces e os demais volumes contêm estado existente de Prometheus/Grafana. Para retirar o serviço, parar a stack com os overlays e subir novamente sem -IncludeTracing; remover órfãos somente do projeto correto. O datasource persistido pode exigir remoção explícita do UID securegate-tempo pela API/UI Grafana autenticada; retirar um arquivo de provisioning não garante excluí-lo do banco Grafana.

A exclusão de tempo-data é uma ação separada sobre dados, depois de identificar o volume do projeto correto. Não executar comandos de limpeza do teste contra uma stack real. Reverter não afeta migrations ou eventos v1. Próximas propostas: **10B4b** exportação OTLP dos serviços; **10B4c** validação integrada/sustentada antes de 10C.

[ADR-064](../adr/064-backend-local-de-tracing.md), [inventário](../OBSERVABILIDADE.md).

## Continuação 10B4b

Exportação OTLP opcional implementada no IAM/enforcer, sem alterar a stack persistente. Ativação, destino local, fila/timeouts, descarte em falha e reversão: [guia de exportação](../runbooks/exportacao-otlp-local.md). A validação integrada/sustentada continua na 10B4c; as limitações de entrega anteriores acima são históricas.

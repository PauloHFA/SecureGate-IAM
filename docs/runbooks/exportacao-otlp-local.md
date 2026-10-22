# Exportação OTLP IAM/enforcer — 10B4b

## Preparar e ativar

1. Compilar com JDK 21 e Maven Wrapper. Preparar/iniciar o [backend local](backend-tracing-local.md) com -IncludeTracing e conferir GET http://127.0.0.1:13200/ready. Não gerar novamente credenciais já preparadas.
2. Usar os scripts e switches de negócio já definidos para cada serviço. Exportação/tracing não habilitam autenticação Transit, publisher ou consumidores. V23 continua obrigatória para o IAM atual; não aplicar migração automaticamente na stack persistente por causa deste guia.
3. Em uma janela PowerShell dedicada ao IAM, definir as variáveis de processo abaixo. Se já usa outros perfis, preservá-los na lista. O perfil structured-logs mantém logs no formato restrito; http-tracing habilita instrumentação/amostragem IAM, mas sozinho não envia spans.

```powershell
$env:SPRING_PROFILES_ACTIVE = 'structured-logs,http-tracing'
$env:SECUREGATE_TRACING_EXPORT_ENABLED = 'true'
$env:SECUREGATE_TRACING_EXPORT_ENDPOINT = 'http://127.0.0.1:14318/v1/traces'
.\infra\scripts\Start-IamCore.ps1
# Acrescentar ao comando os switches de negócio habituais do ambiente.
```

4. Em outra janela dedicada ao enforcer:

```powershell
$env:SPRING_PROFILES_ACTIVE = 'structured-logs,consumer-tracing'
$env:SECUREGATE_TRACING_EXPORT_ENABLED = 'true'
$env:SECUREGATE_TRACING_EXPORT_ENDPOINT = 'http://127.0.0.1:14318/v1/traces'
.\infra\scripts\Start-PolicyEnforcer.ps1 -EnableConsumer
```

Ativar -EnableConsumer somente com as credenciais/dependências do [consumidor](enforcer-revogacao-local.md) preparadas; combinar switches Vault/métricas existentes quando aplicável. Os exemplos são instruções, não execução efetuada na stack persistente nesta entrega.

5. Se TEMPO_OTLP_PORT foi alterada ao subir o backend, usar a mesma porta em SECUREGATE_TRACING_EXPORT_ENDPOINT nas duas janelas. Somente http://127.0.0.1:<porta>/v1/traces, porta 1024–65535, sem credenciais/parâmetros/fragmento. Hostnames (inclusive localhost), HTTPS e endpoints remotos são recusados. O exporter não segue redirecionamentos HTTP.
6. Amostragem local padrão 0,1: IAM_HTTP_TRACE_PROBABILITY e ENFORCER_TRACING_SAMPLING_PROBABILITY aceitam 0–1. Zero não envia spans; um só para diagnóstico controlado com dados sintéticos. Flags sampled remotas não sobrepõem essa política. Não tratar ausência de um trace como falha de negócio.
7. Consultar pelo Grafana autenticado, datasource Tempo, usando traceId de um fluxo amostrado. service.name diferencia iam-core/policy-enforcer. Auditoria em lote é raiz com links; pesquisar apenas parent pode não mostrar o lote. A jornada real foi validada em fixture descartável na [10B4c](../evidencias/etapa-10b4c.md); ainda falta coleta simultânea sustentada dos demais sinais/exporters para o aceite global 10B.

As variáveis acima são de processo, não setx nem novo segredo no .env. Encerrar a janela remove esse ambiente. Se reutilizar uma janela, restaurar seus valores anteriores ao terminar. Flags de exportação nas duas aplicações são independentes do -IncludeTracing da infraestrutura.

## Falhas, dados e desligamento

Fila 512 spans, lote 128, intervalo 1 s, conexão 500 ms, request 1 s, espera por lote 2 s. Não há retry de exportação nem armazenamento local de spans. Backend indisponível/lento ou fila cheia causa perda de telemetria; processamento de negócio e ACKs continuam pela política existente. Após recuperação, somente lotes novos são enviados. Não reprocessar eventos de negócio para recuperar traces.

Usar apenas spans/atributos finitos do [inventário](../OBSERVABILIDADE.md). Não instalar agente/autoinstrumentação nem acrescentar span.error com exceções ou atributos de body/URL/headers/segredos. SDK exporta os dados que a instrumentação fornece: o exporter não remove campos arbitrários. OTEL_EXPORTER_OTLP_* não configura este exporter explícito. Logs brutos de diagnóstico da biblioteca podem conter respostas do receptor; usar structured-logs e não publicar logs sem revisão.

Para desativar, definir SECUREGATE_TRACING_EXPORT_ENABLED=false e reiniciar os serviços. Manter tracing se ainda deseja IDs locais; desabilitar os perfis também é uma ação separada. Fechar o contexto tenta drenar a fila por lotes, sem garantia de entrega/durabilidade. Não remover volumes ou V23. Se export.enabled=true e management.tracing.enabled=false, startup recusa a combinação; corrigir a configuração antes de reiniciar.

No fechamento do enforcer, disconnect agora usa abort de conexão com limite de 1 s, para não reter indefinidamente o lock do worker após interrupção NIO. Mensagens sem ACK continuam sujeitas a reentrega/deduplicação normal. Correção observada durante validação, independente do exporter; não resolve a ressalva histórica IAM/Hikari.

## Verificação reproduzível

```powershell
.\mvnw.cmd -B -ntp -pl iam-core,policy-enforcer -am test '-Dtest=LocalOtlpExportConfigurationTest' '-Dsurefire.failIfNoSpecifiedTests=false'
.\mvnw.cmd -B -ntp verify
```

O teste usa receptor HTTP local efêmero e providers Spring reais. Verifica protocolo protobuf, recurso/parent/link, amostragem zero, 503/recuperação, timeout, saturação não bloqueante, fechamento e recusa de redirect. Não inicia a stack persistente. O ensaio de saturação limita número de requisições após bloquear o receptor, não mede capacidade produtiva. O teste 10B4a continua verificando ingestão/consulta Tempo com OTLP sintético; a ligação completa entre instrumentação de negócio e backend fica na 10B4c.

[ADR-065](../adr/065-exportacao-otlp-local.md), [evidências](../evidencias/etapa-10b4b.md), [passos](../GUIA-DE-IMPLEMENTACAO.md).

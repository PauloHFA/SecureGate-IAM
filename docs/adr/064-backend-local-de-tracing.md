# ADR-064 — Backend local de tracing

- Estado: 10B4a implementada localmente; validação completa concluída (847 testes; ressalva de shutdown registrada); sem commit/push.
- Data: 21/10/2026.
- Base: 38dd1ae, já publicada; branch feat/tracing-backend.

## Divisão e decisão

Dividir 10B4 em **10B4a backend local/consulta**, **10B4b exportação OTLP IAM/enforcer** e **10B4c validação conjunta sustentada**. A instrumentação anterior cria spans, mas não os exporta. Primeiro validar armazenamento/consulta com dados sintéticos; não declarar a cadeia de aplicação concluída nesta entrega.

Adicionar overlay opcional à stack de observabilidade existente, com **Grafana Tempo 3.1.1** fixado por tag/digest de manifesto verificados em 21/10/2026. Reutilizar Grafana 13.2.4 do Compose base, credenciais privadas existentes e Prometheus como datasource padrão. O backend executa single-binary (-target=all), armazenamento local em volume próprio e OTLP/HTTP. Não introduzir Kafka, collector, metrics-generator/service graph ou novos jobs de scrape nesta entrega.

Portas do host exclusivamente loopback: API/query 13200 → 3200; OTLP/HTTP 14318 → 4318, customizáveis pelas variáveis TEMPO_HTTP_PORT/TEMPO_OTLP_PORT. Não publicar gRPC/Jaeger/Zipkin. Tempo não tem autenticação própria; o limite de acesso é host/rede Docker. O datasource Grafana usa proxy interno http://tempo:3200 e mantém autenticação Grafana existente; isso não autentica o acesso direto às portas loopback. Somente desenvolvimento local confiável, sem expor em LAN/Internet.

Executar UID/GID 10001:10001, raiz somente leitura, volume /var/tempo, tmpfs /tmp limitado, capabilities removidas/no-new-privileges, 1 GiB de memória, uma CPU, 128 PIDs e logs Docker rotacionados. Limitar ingresso a 1 MiB/s com burst de 2 MiB, trace de até 1 MiB, 1000 traces ativos e buffer live-store de 32 MiB. Valores são limites/configuração para avaliação local, não capacidade medida ou garantia contra esgotamento de disco.

Configurar block_retention de 24h no scheduler/worker e ciclo de retenção de 5 min. Exclusão é assíncrona e depende do ciclo/estado dos blocos; não é TTL exato nem quota de volume. Não houve espera de 24h para testar exclusão. Desabilitar relatório de uso, MCP e logging de spans recebidos/descartados. Backend aceita o OTLP enviado; filtros/limites do que sai dos serviços serão parte da 10B4b. Não enviar credenciais/PII em testes.

## Ativação e compatibilidade

Start-Observability.ps1 ganha -IncludeTracing. Sem switch, Compose base e datasource padrão continuam anteriores. Com switch, resolver configuração/portas antes de up, recusar colisões e aguardar /ready do host com timeout. A imagem Tempo é distroless; não inventar healthcheck que depende de shell inexistente. Serviço running no Docker não substitui readiness da API.

A definição real do datasource Tempo fica fora do diretório base e só é montada pelo overlay; o base contém apenas um arquivo de mountpoint com apiVersion 1/datasources vazio, necessário ao bind aninhado em diretório read-only; não substituir diretório de provisioning nem tornar Tempo padrão. Não gerar/trocar senha Grafana ou token de métricas e não alterar .env/stack persistente do usuário. Nenhum código/dependência de aplicação, schema ou protocolo de eventos foi alterado.

## Verificação e limites

TracingBackendIT renderiza Compose base/overlay e usa as imagens/configs versionadas em containers reais. Envia OTLP sintético, consulta trace com link, verifica Grafana sem credencial/consulta autenticada, mantém Prometheus padrão e reinicia backend preservando volume. Isso não demonstra exportação das aplicações, UI visual automatizada, desempenho ou retenção física em 24h. Validação dos scripts usa workspace/projeto descartáveis separados da stack do usuário.

Reversão: parar a stack usando os mesmos overlays; reiniciar sem -IncludeTracing/remove-orphans para retirar o serviço opcional. Provisioning Grafana não garante remoção automática do datasource já gravado: remover explicitamente securegate-tempo pela API/UI autenticada se necessário. Preservar volume por padrão; exclusão de dados exige escolha explícita do volume/projeto correto. Não usar down --volumes na stack real para desativar tracing.

## Fontes primárias verificadas

- [Release Tempo 3.1.1](https://github.com/grafana/tempo/releases/tag/v3.1.1).
- [Exemplo single-binary da versão](https://github.com/grafana/tempo/tree/v3.1.1/example/docker-compose/single-binary).
- [Manifesto de configuração 3.1.1](https://github.com/grafana/tempo/blob/v3.1.1/docs/sources/tempo/configuration/manifest.md).
- [Autenticação do Tempo](https://grafana.com/docs/tempo/latest/operations/authentication/).

[Operação](../runbooks/backend-tracing-local.md), [evidências](../evidencias/etapa-10b4a.md).

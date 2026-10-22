# Evidências — 10B4b: exportação OTLP local

- Data: 21/10/2026, America/Sao_Paulo.
- Base: 38dd1ae; branch feat/tracing-backend, com a 10B4a anterior incluída no mesmo commit local.
- Estado: implementada e validada localmente; commit local nesta branch, sem push/CI remota.

## Implementação e verificação

Configuração de bootstrap em IAM/enforcer acrescenta BatchSpanProcessor ao provider explícito existente. Exportação false por padrão; true exige tracing habilitado e endpoint local validado. Fila 512/lote 128/intervalo 1 s; conexão 500 ms/request 1 s/espera do processor 2 s; sem retry. Recursos existentes service.name e dados/parents/links preservados. Nenhuma mudança em domínio, mensagens v1, schema ou credenciais/stack persistente.

Primeiras seleções com transporte padrão aprovaram 36 casos. Na revisão, identificado que OkHttp permite redirect; substituído pelo sender JDK do mesmo BOM e acrescentado teste 307 nos dois módulos. A primeira execução de verify foi interrompida para concluir essa correção e não conta como build aprovado. A seleção imediatamente posterior encontrou classes temporariamente indisponíveis no target após interrupção; executada compilação limpa para validar novamente, sem alterar código de negócio ou relaxar testes.

Seleção limpa final do exporter: BUILD SUCCESS às 22:51:15 UTC−03, 27,329 s, exit 0; 38 casos (19 por módulo), zero falhas/erros/skips. Log .firecrawl/etapa10b4b-jdk-targeted-final.log. Testes verificam provider real + receiver HTTP/protobuf, recurso/parent/link, default off, destinos recusados, zero sampling, fila cheia, 503/recuperação, timeout, fechamento e redirect.

A execução seguinte de verify (.firecrawl/etapa10b4b-verify-final.log) aprovou testes/gate IAM e chegou a VaultKvBootstrapIT do enforcer, mas ficou bloqueada no fechamento do worker. Thread dump capturado em .firecrawl/etapa10b4b-enforcer-threads.log: main em RabbitRevocationWorker.close aguardando monitor, tick em AMQConnection.abort/BlockingRpcContinuation após interrupção NIO. Execução interrompida; não contar como aprovação completa, independentemente do exit do Wrapper ao interromper processos. Reapareceu também a ressalva histórica do fork IAM após seus testes aprovados.

Teste novo RevocationConsumerIT.interruptedNioFailureDisconnectsWithinBoundAndRequeuesUnackedMessage: RabbitMQ/NIO reais, interrupção antes do ACK. Antes da correção, **1 falha esperada**, assertion de encerramento em até 5 s não atendida, BUILD FAILURE (.firecrawl/etapa10b4b-nio-red.log). Corrigir connection.abort() para abort(1000), que força socket close após o limite; não aumentar timeouts nem alterar política de negócio. A recuperação por outro worker verifica preservação da entrega sem ACK.

Após a correção, seleção real com regressão NIO e VaultKvBootstrapIT: **3 testes aprovados**, zero falhas/erros/skips, BUILD SUCCESS e exit nativo 0 (.firecrawl/etapa10b4b-nio-green.log). O teste de regressão inclui recuperação da entrega por outro worker. Novo verify completo iniciado sobre essa versão; não somar seleções sobrepostas ao total final.

Build final: **BUILD SUCCESS às 23:15:31 UTC−03**, 11 min 30 s, exit code nativo **0**. **886 testes**, zero falhas/erros/skips: security-events 43; IAM 432 unitários + 301 integrações; enforcer 72 unitários + 25 integrações; módulo ponta a ponta 13 (PropagationIT 11 + TracingBackendIT 2). Dois gates JaCoCo e arquitetura atendidos. Log .firecrawl/etapa10b4b-verify-complete.log, ignorado pelo Git.

VaultKvBootstrapIT do enforcer encerrou normalmente (2 casos, 8,995 s), e RevocationConsumerIT passou com 21 casos, incluindo a nova regressão. O bloqueio NIO observado foi corrigido; o aviso histórico de encerramento forçado do fork IAM após 30 s de System.exit(0) reapareceu após seus 301 testes aprovados. Esse aviso não foi escondido nem declarado resolvido; nenhum timeout de teste foi aumentado. O reactor concluiu com sucesso. Seleções anteriores/red-green não são somadas ao total final.

Nenhuma mudança funcional/configuração ocorreu após o início do verify final; somente documentação de resultados. Conferência final: 1.449 links locais válidos. 31 arquivos novos/alterados em UTF-8 sem BOM/whitespace final e git diff --check limpo. Branch feat/tracing-backend. Nenhum container de teste remanescente; apenas os quatro serviços persistentes anteriores continuam em execução. sem alteração por esta entrega.

## Limites

Receptor HTTP descartável testa o transporte real dos providers, mas não é backend Tempo. TracingBackendIT 10B4a cobre separadamente Tempo/Grafana. Não foi executada jornada de negócio inteira com consulta dos spans reais no Tempo, coleta conjunta sustentada ou benchmark; pertencem à 10B4c/10C. Saturação demonstra descarte e chamada não bloqueante; não define SLA ou limite de memória em bytes. Ressalva histórica de shutdown Hikari permanece separada. Sem execução na stack persistente, push/CI/merge.

[ADR-065](../adr/065-exportacao-otlp-local.md), [operação](../runbooks/exportacao-otlp-local.md), [guia](../GUIA-DE-IMPLEMENTACAO.md), [evidências anteriores 10B4a](etapa-10b4a.md).

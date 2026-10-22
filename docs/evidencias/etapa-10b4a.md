# Evidências — 10B4a: backend local de tracing

- Data: 21/10/2026, America/Sao_Paulo.
- Base: 38dd1ae, publicada em origin/feat/outbox-trace-context.
- Branch: feat/tracing-backend; implementação e validação locais concluídas; versionada junto à 10B4b no commit local, sem push/CI remota.

## Escopo

10B4 dividida em a (backend/consulta), b (exportação IAM/enforcer) e c (validação conjunta). Overlay Tempo 3.1.1 por digest de manifesto, configuração local com retenção de blocos de 24h, datasource Grafana opcional e switch -IncludeTracing no startup. Nenhuma aplicação/dependência/schema/.env/stack persistente alterada. Grafana reutiliza imagem/credencial/base Prometheus anteriores.

Fontes oficiais consultadas via skill firecrawl-developer-index e GitHub da versão; release/tag e digest Docker verificados em 21/10/2026. Configuração nativa validada por /tempo -config.file=/etc/tempo.yml -config.verify=true, exit 0. A flag de verificação exige valor explícito; tentativas de linha de comando anteriores foram corrigidas antes da validação aprovada.

## Verificação executada

TracingBackendIT: renderização Compose default sem Tempo e overlay com portas loopback/UID/root read-only/volume; containers reais Tempo/Grafana; ingestão OTLP sintética com link, query direta/proxy autenticado, recusa anônima, Prometheus padrão e consulta após restart mantendo volume. Configs/imagens vêm dos arquivos versionados, sem manter cópias paralelas de configuração no teste.

Primeiras duas seleções falharam no fixture Docker: withCopyFileToContainer tenta gravar na raiz já read-only. A segunda tentativa ainda mantinha a chamada de cópia além do bind; ela foi removida. O fixture passou a usar bind de configuração read-only, como o Compose, preservando a proteção. Não houve falha de lógica de negócio nem redução de asserções. Não contar tentativas intermediárias como validações aprovadas.

A terceira seleção chegou à ingestão/consulta com sucesso, mas o fixture consultava a porta dinâmica antiga após restart. Corrigido para inspecionar o mapeamento atual do Docker, sem aumentar o timeout de readiness; a seleção seguinte confirmou consulta e links após restart. O aviso de replay de um bloco incompleto no shutdown não foi tratado como prova de perda dos traces: os dados sintéticos consultados foram recuperados.

O primeiro smoke dos scripts validou base e recusa de colisão, mas o Docker recusou criar o mountpoint do datasource no diretório pai read-only. Acrescentado placeholder válido sem datasource no base e fixture alterado para usar os mesmos binds de diretório/arquivo, reproduzindo o Compose. Repetir as validações com a configuração final.

Seleção final: **BUILD SUCCESS às 22:04:06 UTC−03**, 1 min 2 s, exit code nativo **0**, dois testes de integração, zero falhas/erros/skips. Usa os binds finais de diretório/arquivo com o placeholder; não somar seleções intermediárias.

Smoke dos scripts em workspace/projeto descartáveis: SCRIPT_SMOKE_SUCCESS. Base iniciou sem datasource Tempo; colisão com porta Grafana recusada antes de up; overlay iniciou Tempo pronto e datasource consultável por API autenticada. Mesmas credenciais preparadas usadas nas duas inicializações. Cleanup confirmou containers, rede e três volumes do projeto único removidos, sem atingir stack persistente. A fixture privada fica em .firecrawl/etapa10b4a-script-smoke.ps1 e o log em .firecrawl/etapa10b4a-script-smoke-final.log; não entram no Git. Sintaxe PowerShell também validada por AST.

No build completo reapareceu o aviso Surefire de encerramento forçado do fork IAM após 30 s de System.exit(0), depois dos testes do módulo aprovados. Nenhum timeout foi aumentado. A ressalva histórica de shutdown Hikari permanece aberta; registrar separadamente do resultado de testes/reactor.

Build completo: **BUILD SUCCESS às 22:17:05 UTC−03**, 11 min 37 s, exit code nativo **0**. **847 testes**, zero falhas/erros/skips: security-events 43; IAM 413 unitários + 301 integrações; enforcer 53 unitários + 24 integrações; módulo ponta a ponta 13 (PropagationIT 11 + TracingBackendIT 2). **Dois gates JaCoCo** e arquitetura atendidos. Totais do log desta execução, sem XMLs antigos de smokes.

O aviso de encerramento forçado do fork IAM não foi ocultado e o reactor terminou com sucesso; segue como ressalva separada. Nenhum código/configuração foi alterado após o início do build completo, apenas documentação de resultados. Conferência final: 17 arquivos novos/alterados UTF-8 sem BOM/whitespace final, 1.418 links locais válidos em README/infra/docs, git diff --check limpo e sintaxe PowerShell validada. Sem containers do projeto descartável remanescentes. Não somar a seleção/smoke com verify. Smokes/seleções sobrepostos não devem ser somados ao verify. Logs de execução ficam em .firecrawl/target ignorados; credenciais sintéticas nunca são impressas nas evidências.

## Limites

Sem exporter em IAM/enforcer, UI visual automatizada, cadeia completa de aplicação, collector, expurgo após 24h ou carga sustentada. Retenção configurada não é quota rígida de disco nem TTL exato. Tempo não autentica acesso direto: host/rede Docker são limite; Grafana protege apenas seu proxy. Sem migração/alteração da stack persistente, push/CI/merge. Ressalva histórica Hikari não é declarada corrigida nesta entrega.

[ADR-064](../adr/064-backend-local-de-tracing.md), [operação](../runbooks/backend-tracing-local.md), [guia](../GUIA-DE-IMPLEMENTACAO.md).

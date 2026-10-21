# Documentação — SecureGate IAM

Começar pelo [guia de implementação passo a passo](GUIA-DE-IMPLEMENTACAO.md): ele reúne a sequência completa, todas as subdivisões executadas, as próximas divisões propostas e o mapa de migrações.

| Documento | Quando consultar |
| --- | --- |
| [Plano de implementação](PLANO-DE-IMPLEMENTACAO.md) | Escopo, dependências, tarefas e critérios de aceite das etapas 0–12. |
| [Guia passo a passo](GUIA-DE-IMPLEMENTACAO.md) | Ordem de construção, motivos das divisões, como validar e como registrar a próxima entrega. |
| [Observabilidade](OBSERVABILIDADE.md) | Inventário de métricas, acesso de leitura, histogramas e limites das entregas 10B. |
| [ADRs](adr/) | Decisões arquiteturais, alternativas e limites de cada entrega. |
| [Guias operacionais](runbooks/) | Preparação, execução, flags, testes e recuperação. |
| [Evidências](evidencias/) | Comandos executados, resultados observados, ambiente e pendências históricas. |

Estado desta revisão: **10B2b1 — métricas/coleta do enforcer implementadas localmente**, em feat/enforcer-prometheus-metrics, sobre 123c4b0, incluída neste commit; push pendente. Próximas implementações: 10B2b2, exporters de dependências, e 10B3, logs/tracing. A 10B2a está versionada em 123c4b0; publicação/CI e aceite global de observabilidade/capacidade continuam pendentes.

Consultar [inventário](OBSERVABILIDADE.md), [runbook do enforcer](runbooks/metricas-enforcer-local.md), [ADR-052](adr/052-metricas-opcionais-do-enforcer.md) e [evidências 10B2b1](evidencias/etapa-10b2b1.md). Listener opcional com token próprio; modo padrão sem HTTP/MVC preservado. Smoke exige fato/projeção/ACK reais e controle de credencial; exporters/tracing/capacidade permanecem pendentes. O [aceite local da etapa 9](runbooks/aceite-etapa9.md) preserva seus riscos e limites.

Ao implementar uma nova subdivisão, atualizar guia, plano, README, decisões aplicáveis, operação e evidências. Proposta, implementação, validação local, commit, publicação, CI e merge devem permanecer identificáveis separadamente.

O histórico das entregas anteriores e seus resultados permanece nas evidências individuais. O guia referencia também a 10B2b1 e registra suas divisões passo a passo.

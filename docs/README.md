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

Estado desta revisão: **10B4c — cadeia integrada de tracing validada localmente**, em feat/tracing-backend sobre 81aeffa, versionada em commit local, sem push/CI remota. O teste com IAM, outbox, RabbitMQ, enforcer, auditoria, Tempo e Grafana reais comprovou traces consultáveis, links de lote, autenticação do proxy, continuidade do negócio durante pausa do coletor e dez eventos após retorno. `verify` completo: **887 testes aprovados**, dois gates de cobertura e arquitetura atendidos. [Procedimento e limites](evidencias/etapa-10b4c.md). Backend 10B4a e exportação 10B4b foram validados antes; ressalva histórica do fork IAM permanece. O aceite global 10B ainda exige coleta simultânea sustentada de métricas, logs, traces e exporters antes da medição 10C. Publicação/CI e desempenho pendentes. Nenhuma stack persistente alterada.

Ao implementar uma nova subdivisão, atualizar guia, plano, README, decisões aplicáveis, operação e evidências. Proposta, implementação, validação local, commit, publicação, CI e merge devem permanecer identificáveis separadamente.

O histórico das entregas anteriores e seus resultados permanece nas evidências individuais. O guia referencia também as 10B4a/b e registra suas divisões passo a passo.

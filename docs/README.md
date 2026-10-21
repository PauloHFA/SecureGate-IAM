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

Estado desta revisão: **10B3a — logs operacionais JSON implementados localmente**, em feat/structured-operational-logs sobre 771487a, incluída neste commit; push pendente. Build completo aprovado (793 testes), seguido de revisão final direcionada (11 testes); contagens sobrepostas. Próximas: 10B3b tracing HTTP e 10B3c contexto outbox/RabbitMQ. Consultar [operação](runbooks/logs-estruturados-local.md), [ADR-057](adr/057-logs-operacionais-json-restritos.md) e [evidências](evidencias/etapa-10b3a.md). A 10B2b2d está versionada em 771487a. Publicação/CI e aceite global de observabilidade/capacidade continuam pendentes.

Ao implementar uma nova subdivisão, atualizar guia, plano, README, decisões aplicáveis, operação e evidências. Proposta, implementação, validação local, commit, publicação, CI e merge devem permanecer identificáveis separadamente.

O histórico das entregas anteriores e seus resultados permanece nas evidências individuais. O guia referencia também a 10B3a e registra suas divisões passo a passo.

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

Estado desta revisão: **10B3c3a — tracing do consumo no enforcer implementado e validado localmente**, em feat/outbox-trace-context sobre be06e27, incluída neste commit; push pendente. Build completo: 835 testes aprovados, cobertura/arquitetura atendidas. Contexto restrito por entrega nas duas filas, limpeza de escopo/MDC e JSON estruturado com sete campos. Sem nova migração/topologia/exporter. [Operação](runbooks/tracing-consumidores-local.md), [ADR-062](adr/062-tracing-consumo-enforcer.md), [evidências](evidencias/etapa-10b3c3a.md). Próxima: 10B3c3b auditoria em lote IAM; a 10B3c3 completa permanece pendente. Stack persistente preservada; publicação/CI e aceite global pendentes.

Ao implementar uma nova subdivisão, atualizar guia, plano, README, decisões aplicáveis, operação e evidências. Proposta, implementação, validação local, commit, publicação, CI e merge devem permanecer identificáveis separadamente.

O histórico das entregas anteriores e seus resultados permanece nas evidências individuais. O guia referencia também a 10B3c3a e registra suas divisões passo a passo.

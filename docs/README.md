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

Estado desta revisão: **10B3c3b — tracing da auditoria IAM em lote implementado e validado localmente**, em feat/outbox-trace-context sobre 103553e, incluída neste commit; push pendente. Build completo: 845 testes aprovados, cobertura/arquitetura atendidas; encerramento forçado do fork IAM/Hikari registrado nas evidências. Raiz por lote com links de contextos distintos, fallback com parents individuais e limpeza de escopo/MDC. Sem novo schema/exporter. [Operação](runbooks/tracing-consumidores-local.md), [ADR-063](adr/063-tracing-auditoria-em-lote.md), [evidências](evidencias/etapa-10b3c3b.md). Instrumentação 10B3c3 concluída, aceite global 10B aberto. Próxima proposta: 10B4 backend/exportação/coleta conjunta, antes de 10C. Stack persistente preservada; publicação/CI e desempenho pendentes.

Ao implementar uma nova subdivisão, atualizar guia, plano, README, decisões aplicáveis, operação e evidências. Proposta, implementação, validação local, commit, publicação, CI e merge devem permanecer identificáveis separadamente.

O histórico das entregas anteriores e seus resultados permanece nas evidências individuais. O guia referencia também a 10B3c3b e registra suas divisões passo a passo.

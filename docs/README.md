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

Estado desta revisão: **10B3c1 — contexto persistido no outbox implementado e validado localmente**, em feat/outbox-trace-context sobre 32f3bfa, incluída neste commit; push pendente. Build completo: 818 testes aprovados, cobertura/arquitetura atendidas. V23 deve preceder o novo binário. [Operação](runbooks/tracing-outbox-local.md), [ADR-060](adr/060-contexto-duravel-no-outbox.md), [evidências](evidencias/etapa-10b3c1.md). Próximas: 10B3c2 publicação AMQP e 10B3c3 consumo. Stack persistente não migrada; publicação/CI e aceite global pendentes.

Ao implementar uma nova subdivisão, atualizar guia, plano, README, decisões aplicáveis, operação e evidências. Proposta, implementação, validação local, commit, publicação, CI e merge devem permanecer identificáveis separadamente.

O histórico das entregas anteriores e seus resultados permanece nas evidências individuais. O guia referencia também a 10B3c1 e registra suas divisões passo a passo.

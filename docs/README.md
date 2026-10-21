# Documentação — SecureGate IAM

Começar pelo [guia de implementação passo a passo](GUIA-DE-IMPLEMENTACAO.md): ele reúne a sequência completa, todas as subdivisões executadas, as próximas divisões propostas e o mapa de migrações.

| Documento | Quando consultar |
| --- | --- |
| [Plano de implementação](PLANO-DE-IMPLEMENTACAO.md) | Escopo, dependências, tarefas e critérios de aceite das etapas 0–12. |
| [Guia passo a passo](GUIA-DE-IMPLEMENTACAO.md) | Ordem de construção, motivos das divisões, como validar e como registrar a próxima entrega. |
| [ADRs](adr/) | Decisões arquiteturais, alternativas e limites de cada entrega. |
| [Guias operacionais](runbooks/) | Preparação, execução, flags, testes e recuperação. |
| [Evidências](evidencias/) | Comandos executados, resultados observados, ambiente e pendências históricas. |

Estado desta revisão: **9D — aceite técnico local da etapa 9 consolidado**, sobre 6d08c2a; revisão documental incluída neste commit, sem novo build; push pendente em docs/stage-nine-acceptance. Próxima implementação local: 10A, massa sintética e planos JMeter. Aceite global/remoto, CI e verificações de entrega aplicáveis permanecem pendentes.

Consultar [matriz de controles e reprodução](runbooks/aceite-etapa9.md), [evidências 9D](evidencias/etapa-09d.md) e [último build funcional, 9A3](evidencias/etapa-09a3.md): 778 testes aprovados em 20/10/2026 às 22:01:30 UTC−03. Para infraestrutura, consultar [infra/README](../infra/README.md).

Ao implementar uma nova subdivisão, atualizar guia, plano, README, decisões aplicáveis, operação e evidências. Proposta, implementação, validação local, commit, publicação, CI e merge devem permanecer identificáveis separadamente.

O histórico das entregas anteriores e seus resultados permanece nas evidências individuais. O guia referencia também a revisão 9D e registra suas divisões passo a passo.

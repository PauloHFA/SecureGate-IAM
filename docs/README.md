# Documentação — SecureGate IAM

Começar pelo [guia de implementação passo a passo](GUIA-DE-IMPLEMENTACAO.md): ele reúne a sequência completa, todas as subdivisões executadas, as próximas divisões propostas e o mapa de migrações.

| Documento | Quando consultar |
| --- | --- |
| [Plano de implementação](PLANO-DE-IMPLEMENTACAO.md) | Escopo, dependências, tarefas e critérios de aceite das etapas 0–12. |
| [Guia passo a passo](GUIA-DE-IMPLEMENTACAO.md) | Ordem de construção, motivos das divisões, como validar e como registrar a próxima entrega. |
| [ADRs](adr/) | Decisões arquiteturais, alternativas e limites de cada entrega. |
| [Guias operacionais](runbooks/) | Preparação, execução, flags, testes e recuperação. |
| [Evidências](evidencias/) | Comandos executados, resultados observados, ambiente e pendências históricas. |

Estado desta revisão: **10A2b3 — auditoria implementada localmente**, em feat/load-audit-smoke, sobre 860d125; 10A2b2/10A2b3 incluídas neste commit; push pendente. Próxima implementação: 10B, observabilidade. Planos funcionais da 10A disponíveis; capacidade/aceite global da etapa 10, publicação/CI e verificações de entrega permanecem pendentes.

Consultar [operação de auditoria](../load-tests/jmeter/AUDIT.md), [runbook](runbooks/carga-auditoria-local.md), [ADR-049](adr/049-carga-de-auditoria-central.md) e [evidências 10A2b3](evidencias/etapa-10a2b3.md). O smoke opt-in verifica fatos reais e cursor com resultados redigidos; não comprova taxa máxima de ingestão ou SLA. O [aceite local da etapa 9](runbooks/aceite-etapa9.md) preserva seus riscos e limites.

Ao implementar uma nova subdivisão, atualizar guia, plano, README, decisões aplicáveis, operação e evidências. Proposta, implementação, validação local, commit, publicação, CI e merge devem permanecer identificáveis separadamente.

O histórico das entregas anteriores e seus resultados permanece nas evidências individuais. O guia referencia também a 10A2b3 e registra suas divisões passo a passo.

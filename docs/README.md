# Documentação — SecureGate IAM

Começar pelo [guia de implementação passo a passo](GUIA-DE-IMPLEMENTACAO.md): ele reúne a sequência completa, todas as subdivisões executadas, as próximas divisões propostas e o mapa de migrações.

| Documento | Quando consultar |
| --- | --- |
| [Plano de implementação](PLANO-DE-IMPLEMENTACAO.md) | Escopo, dependências, tarefas e critérios de aceite das etapas 0–12. |
| [Guia passo a passo](GUIA-DE-IMPLEMENTACAO.md) | Ordem de construção, motivos das divisões, como validar e como registrar a próxima entrega. |
| [ADRs](adr/) | Decisões arquiteturais, alternativas e limites de cada entrega. |
| [Guias operacionais](runbooks/) | Preparação, execução, flags, testes e recuperação. |
| [Evidências](evidencias/) | Comandos executados, resultados observados, ambiente e pendências históricas. |

Estado desta revisão: **10A2a — massa humana e smoke PKCE/replay implementados localmente**, em feat/load-human-pkce-smoke, sobre 1e7a201, incluída neste commit; push pendente. Próxima implementação: 10A2b, demais cenários. Publicação/CI e verificações de entrega permanecem separadas.

Consultar [operação humana](../load-tests/jmeter/HUMAN-PKCE.md), [runbook](runbooks/carga-humana-pkce-local.md), [ADR-046](adr/046-carga-humana-pkce.md) e [evidências 10A2a](evidencias/etapa-10a2a.md). O smoke opt-in é separado do build padrão; resultados não comprovam capacidade. O [aceite local da etapa 9](runbooks/aceite-etapa9.md) preserva seus riscos e limites.

Ao implementar uma nova subdivisão, atualizar guia, plano, README, decisões aplicáveis, operação e evidências. Proposta, implementação, validação local, commit, publicação, CI e merge devem permanecer identificáveis separadamente.

O histórico das entregas anteriores e seus resultados permanece nas evidências individuais. O guia referencia também a 10A2a e registra suas divisões passo a passo.

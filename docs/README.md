# Documentação — SecureGate IAM

Começar pelo [guia de implementação passo a passo](GUIA-DE-IMPLEMENTACAO.md): ele reúne a sequência completa, todas as subdivisões executadas, as próximas divisões propostas e o mapa de migrações.

| Documento | Quando consultar |
| --- | --- |
| [Plano de implementação](PLANO-DE-IMPLEMENTACAO.md) | Escopo, dependências, tarefas e critérios de aceite das etapas 0–12. |
| [Guia passo a passo](GUIA-DE-IMPLEMENTACAO.md) | Ordem de construção, motivos das divisões, como validar e como registrar a próxima entrega. |
| [ADRs](adr/) | Decisões arquiteturais, alternativas e limites de cada entrega. |
| [Guias operacionais](runbooks/) | Preparação, execução, flags, testes e recuperação. |
| [Evidências](evidencias/) | Comandos executados, resultados observados, ambiente e pendências históricas. |

Estado desta revisão: **10A1 — massa de clientes e smoke JMeter implementados localmente**, em feat/load-client-credentials-smoke, sobre 255fdff, incluída neste commit; publicação/CI a verificar separadamente. Próxima implementação: 10A2, massa humana/PKCE e demais cenários. Publicação/CI e verificações de entrega permanecem pendentes.

Consultar [operação JMeter](../load-tests/jmeter/README.md), [runbook](runbooks/carga-client-credentials-local.md), [ADR-045](adr/045-base-de-carga-jmeter.md) e [evidências 10A1](evidencias/etapa-10a1.md). O smoke opt-in é separado do build funcional padrão; contagens/resultados não representam medição de capacidade. O [aceite local da etapa 9](runbooks/aceite-etapa9.md) preserva seus riscos e limites.

Ao implementar uma nova subdivisão, atualizar guia, plano, README, decisões aplicáveis, operação e evidências. Proposta, implementação, validação local, commit, publicação, CI e merge devem permanecer identificáveis separadamente.

O histórico das entregas anteriores e seus resultados permanece nas evidências individuais. O guia referencia também a 10A1 e registra suas divisões passo a passo.

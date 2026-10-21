# Documentação — SecureGate IAM

Começar pelo [guia de implementação passo a passo](GUIA-DE-IMPLEMENTACAO.md): ele reúne a sequência completa, todas as subdivisões executadas, as próximas divisões propostas e o mapa de migrações.

| Documento | Quando consultar |
| --- | --- |
| [Plano de implementação](PLANO-DE-IMPLEMENTACAO.md) | Escopo, dependências, tarefas e critérios de aceite das etapas 0–12. |
| [Guia passo a passo](GUIA-DE-IMPLEMENTACAO.md) | Ordem de construção, motivos das divisões, como validar e como registrar a próxima entrega. |
| [ADRs](adr/) | Decisões arquiteturais, alternativas e limites de cada entrega. |
| [Guias operacionais](runbooks/) | Preparação, execução, flags, testes e recuperação. |
| [Evidências](evidencias/) | Comandos executados, resultados observados, ambiente e pendências históricas. |

Estado desta revisão: **10A2b1 — falhas de login implementadas localmente**, em feat/load-login-failures-smoke, sobre 8580b4e, incluída neste commit; push pendente. Próximas implementações: 10A2b2, recursos/revogação, e 10A2b3, auditoria. Publicação/CI e verificações de entrega permanecem separadas.

Consultar [operação de negativas](../load-tests/jmeter/LOGIN-FAILURES.md), [runbook](runbooks/carga-falhas-login-local.md), [ADR-047](adr/047-carga-de-falhas-de-login.md) e [evidências 10A2b1](evidencias/etapa-10a2b1.md). O smoke opt-in é separado do build padrão; resultados não comprovam capacidade ou tempo constante. O [aceite local da etapa 9](runbooks/aceite-etapa9.md) preserva seus riscos e limites.

Ao implementar uma nova subdivisão, atualizar guia, plano, README, decisões aplicáveis, operação e evidências. Proposta, implementação, validação local, commit, publicação, CI e merge devem permanecer identificáveis separadamente.

O histórico das entregas anteriores e seus resultados permanece nas evidências individuais. O guia referencia também a 10A2b1 e registra suas divisões passo a passo.

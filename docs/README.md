# Documentação — SecureGate IAM

Começar pelo [guia de implementação passo a passo](GUIA-DE-IMPLEMENTACAO.md): ele reúne a sequência completa, todas as subdivisões executadas, as próximas divisões propostas e o mapa de migrações.

| Documento | Quando consultar |
| --- | --- |
| [Plano de implementação](PLANO-DE-IMPLEMENTACAO.md) | Escopo, dependências, tarefas e critérios de aceite das etapas 0–12. |
| [Guia passo a passo](GUIA-DE-IMPLEMENTACAO.md) | Ordem de construção, motivos das divisões, como validar e como registrar a próxima entrega. |
| [ADRs](adr/) | Decisões arquiteturais, alternativas e limites de cada entrega. |
| [Guias operacionais](runbooks/) | Preparação, execução, flags, testes e recuperação. |
| [Evidências](evidencias/) | Comandos executados, resultados observados, ambiente e pendências históricas. |

Estado desta revisão: **9A3 implementada localmente, com 778 testes aprovados**, na branch feat/enforcer-vault-kv, sobre a9b67f0; incluída neste commit; push/CI remota pendentes. Próximo trabalho: revisão de controles e aceite global da etapa 9, depois 10A.

Para esta entrega, consultar [KV do enforcer](runbooks/enforcer-vault-kv-local.md), [ADR-044](adr/044-bootstrap-kv-enforcer.md) e [evidências 9A3](evidencias/etapa-09a3.md), incluindo resultados da validação. Para infraestrutura, consultar [infra/README](../infra/README.md).

Ao implementar uma nova subdivisão, atualizar guia, plano, README, decisões aplicáveis, operação e evidências. Proposta, implementação, validação local, commit, publicação, CI e merge devem permanecer identificáveis separadamente.

O histórico das entregas anteriores e seus resultados permanece nas evidências individuais. O guia referencia também a 9A3 e registra suas divisões passo a passo.

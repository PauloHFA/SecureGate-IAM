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

Estado desta revisão: **10C3 — consolidação e runbook unificado de carga**, em feat/load-comparative-baseline, validada localmente. O runner unificado `Run-ComparativeBaseline.ps1` orquestra os cinco fluxos de carga (`issuance`, `introspection`, `resource`, `revocation` e `audit`) com parâmetros uniformes e consolida os resultados em `comparative-summary.json` e `comparative-summary.csv`. A seleção descartável `ComparativeBaselineSmoke` passou com 1 teste, zero falhas e todas as metas atendidas. [Procedimento e limites](evidencias/etapa-10c3.md). Investigação de capacidade segue na próxima divisão **10D**. Nenhuma stack persistente alterada.

Ao implementar uma nova subdivisão, atualizar guia, plano, README, decisões aplicáveis, operação e evidências. Proposta, implementação, validação local, commit, publicação, CI e merge devem permanecer identificáveis separadamente.

O histórico das entregas anteriores e seus resultados permanece nas evidências individuais. O guia referencia também as 10B4a/b e registra suas divisões passo a passo.
